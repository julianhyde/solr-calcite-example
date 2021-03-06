/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.handler.sql;

import org.apache.calcite.plan.*;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.Pair;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of a {@link org.apache.calcite.rel.core.Filter} relational expression in Solr.
 */
class SolrFilter extends Filter implements SolrRel {
  SolrFilter(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelNode child,
      RexNode condition) {
    super(cluster, traitSet, child, condition);
    assert getConvention() == SolrRel.CONVENTION;
    assert getConvention() == child.getConvention();
  }

  @Override public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
    return super.computeSelfCost(planner, mq).multiplyBy(0.1);
  }

  public SolrFilter copy(RelTraitSet traitSet, RelNode input, RexNode condition) {
    return new SolrFilter(getCluster(), traitSet, input, condition);
  }

  public void implement(Implementor implementor) {
    implementor.visitChild(0, getInput());
    Translator translator = new Translator(SolrRules.solrFieldNames(getRowType()));
    String query = translator.translateMatch(condition);
    implementor.addQuery(query);
  }

  /** Translates {@link RexNode} expressions into Solr query strings. */
  private static class Translator {
    private final List<String> fieldNames;

    Translator(List<String> fieldNames) {
      this.fieldNames = fieldNames;
    }

    private String translateMatch(RexNode condition) {
      return translateOr(condition);
    }

    private String translateOr(RexNode condition) {
      List<String> ors = new ArrayList<>();
      for (RexNode node : RelOptUtil.disjunctions(condition)) {
        ors.add(translateAnd(node));
      }
      return String.join(" OR ", ors);
    }

    private String translateAnd(RexNode node0) {
      List<String> ands = new ArrayList<>();
      for (RexNode node : RelOptUtil.conjunctions(node0)) {
        ands.add(translateMatch2(node));
      }

      return String.join(" AND ", ands);
    }

    private String translateMatch2(RexNode node) {
      Pair<String, RexLiteral> binaryTranslated = translateBinary((RexCall) node);

      switch (node.getKind()) {
//        case NOT:
//          return translateBinary("-", "-", (RexCall) node);
        case EQUALS:
          return binaryTranslated.getKey() + ":" + binaryTranslated.getValue().getValue2();
        case NOT_EQUALS:
          return "-" + binaryTranslated.getKey() + ":" + binaryTranslated.getValue().getValue2();
        case LESS_THAN:
          return binaryTranslated.getKey() + ": [ * TO " + binaryTranslated.getValue().getValue2() + " }";
        case LESS_THAN_OR_EQUAL:
          return binaryTranslated.getKey() + ": [ * TO " + binaryTranslated.getValue().getValue2() + " ]";
        case GREATER_THAN:
          return binaryTranslated.getKey() + ": { " + binaryTranslated.getValue().getValue2() + " TO * ]";
        case GREATER_THAN_OR_EQUAL:
          return binaryTranslated.getKey() + ": [ " + binaryTranslated.getValue().getValue2() + " TO * ]";
        default:
          throw new AssertionError("cannot translate " + node);
      }
    }

    /** Translates a call to a binary operator, reversing arguments if necessary. */
    private Pair<String, RexLiteral> translateBinary(RexCall call) {
      List<RexNode> operands = call.getOperands();
      if(operands.size() != 2) {
        throw new AssertionError("Invalid number of arguments - " + operands.size());
      }
      final RexNode left = operands.get(0);
      final RexNode right = operands.get(1);
      final Pair<String, RexLiteral> a = translateBinary2(left, right);
      if (a != null) {
        return a;
      }
      final Pair<String, RexLiteral> b = translateBinary2(right, left);
      if (b != null) {
        return b;
      }
      throw new AssertionError("cannot translate call " + call);
    }

    /** Translates a call to a binary operator. Returns whether successful. */
    private Pair<String, RexLiteral> translateBinary2(RexNode left, RexNode right) {
      switch (right.getKind()) {
        case LITERAL:
          break;
        default:
          return null;
      }
      final RexLiteral rightLiteral = (RexLiteral) right;
      switch (left.getKind()) {
        case INPUT_REF:
          final RexInputRef left1 = (RexInputRef) left;
          String name = fieldNames.get(left1.getIndex());
          return new Pair<>(name, rightLiteral);
        case CAST:
          return translateBinary2(((RexCall) left).operands.get(0), right);
//        case OTHER_FUNCTION:
//          String itemName = SolrRules.isItem((RexCall) left);
//          if (itemName != null) {
//            return translateOp2(op, itemName, rightLiteral);
//          }
        default:
          return null;
      }
    }
  }
}
