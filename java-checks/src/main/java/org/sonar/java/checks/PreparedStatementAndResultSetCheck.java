/*
 * SonarQube Java
 * Copyright (C) 2012 SonarSource
 * sonarqube@googlegroups.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.java.checks;

import com.google.common.collect.ImmutableList;
import org.apache.commons.lang.StringUtils;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonar.check.Priority;
import org.sonar.check.Rule;
import org.sonar.java.checks.helpers.ExpressionsHelper;
import org.sonar.java.checks.helpers.ReassignmentFinder;
import org.sonar.java.checks.methods.AbstractMethodDetection;
import org.sonar.java.checks.methods.MethodMatcher;
import org.sonar.java.checks.methods.NameCriteria;
import org.sonar.java.checks.methods.TypeCriteria;
import org.sonar.java.model.LiteralUtils;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.Arguments;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.LiteralTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.VariableTree;
import org.sonar.squidbridge.annotations.ActivatedByDefault;
import org.sonar.squidbridge.annotations.SqaleConstantRemediation;
import org.sonar.squidbridge.annotations.SqaleSubCharacteristic;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

import java.util.List;

@Rule(
  key = "S2695",
  name = "\"PreparedStatement\" and \"ResultSet\" methods should be called with valid indices",
  tags = {"bug", "sql"},
  priority = Priority.BLOCKER)
@ActivatedByDefault
@SqaleSubCharacteristic(RulesDefinition.SubCharacteristics.INSTRUCTION_RELIABILITY)
@SqaleConstantRemediation("2min")
public class PreparedStatementAndResultSetCheck extends AbstractMethodDetection {

  private static final String INT = "int";
  private static final String JAVA_SQL_RESULTSET = "java.sql.ResultSet";

  @Override
  protected List<MethodMatcher> getMethodInvocationMatchers() {
    return ImmutableList.of(
      MethodMatcher.create().typeDefinition("java.sql.PreparedStatement").name(NameCriteria.startsWith("set")).addParameter(INT).addParameter(TypeCriteria.anyType()),
      MethodMatcher.create().typeDefinition(JAVA_SQL_RESULTSET).name(NameCriteria.startsWith("get")).addParameter(INT),
      MethodMatcher.create().typeDefinition(JAVA_SQL_RESULTSET).name(NameCriteria.startsWith("get")).addParameter(INT).addParameter(TypeCriteria.anyType()));
  }

  @Override
  protected void onMethodInvocationFound(MethodInvocationTree mit) {
    Integer methodFirstArgumentAsInteger = LiteralUtils.intLiteralValue(mit.arguments().get(0));
    if (methodFirstArgumentAsInteger == null) {
      // nothing to say if first argument can not be evaluated
      return;
    }

    boolean isMethodFromJavaSqlResultSet = mit.symbol().owner().type().is(JAVA_SQL_RESULTSET);
    int methodFirstArgumentValue = methodFirstArgumentAsInteger.intValue();

    if (isMethodFromJavaSqlResultSet && methodFirstArgumentValue == 0) {
      addIssue(mit, "ResultSet indices start at 1.");
    } else if (!isMethodFromJavaSqlResultSet) {
      if (methodFirstArgumentValue == 0) {
        addIssue(mit, "PreparedStatement indices start at 1.");
      } else {
        Tree preparedStatementReference = getPreparedStatementReference(mit);
        Integer numberParameters = getPreparedStatementNumberOfParameters(preparedStatementReference);
        if (numberParameters != null && methodFirstArgumentValue > numberParameters) {
          addIssue(mit, "This \"PreparedStatement\" " + (numberParameters == 0 ? "has no" : ("only has " + numberParameters)) + " parameters.");
        }
      }
    }
  }

  @CheckForNull
  private static Tree getPreparedStatementReference(MethodInvocationTree mit) {
    ExpressionTree methodSelect = mit.methodSelect();
    if (methodSelect.is(Tree.Kind.MEMBER_SELECT)) {
      ExpressionTree expression = ((MemberSelectExpressionTree) methodSelect).expression();
      if (expression.is(Tree.Kind.IDENTIFIER)) {
        Symbol referenceSymbol = ((IdentifierTree) expression).symbol();
        return ReassignmentFinder.getClosestReassignmentOrDeclaration(mit, referenceSymbol);
      }
    }
    return null;
  }

  @CheckForNull
  private static Integer getPreparedStatementNumberOfParameters(@Nullable Tree tree) {
    if (tree != null) {
      ExpressionTree initializer = tree.is(Tree.Kind.VARIABLE) ?
        ((VariableTree) tree).initializer() :
        ((AssignmentExpressionTree) tree).expression();
      if (initializer != null && initializer.is(Tree.Kind.METHOD_INVOCATION)) {
        Arguments arguments = ((MethodInvocationTree) initializer).arguments();
        if (!arguments.isEmpty()) {
          return getNumberQuery(arguments.get(0));
        }
      }
    }
    return null;
  }

  @CheckForNull
  private static Integer getNumberQuery(ExpressionTree expression) {
    ExpressionTree expr = ExpressionsHelper.skipParentheses(expression);
    if (expr.is(Tree.Kind.IDENTIFIER)) {
      return handleVariableUsedAsQuery((IdentifierTree) expr);
    } else if (expr.is(Tree.Kind.PLUS)) {
      return handleStringConcatenation((BinaryExpressionTree) expr);
    }
    return countQuery(expr);
  }

  private static Integer handleVariableUsedAsQuery(IdentifierTree identifier) {
    Tree lastAssignment = ReassignmentFinder.getClosestReassignmentOrDeclaration(identifier, identifier.symbol());
    if (lastAssignment != null) {
      ExpressionTree initializer = lastAssignment.is(Tree.Kind.VARIABLE) ? ((VariableTree) lastAssignment).initializer()
        : ((AssignmentExpressionTree) lastAssignment).expression();
      return initializer != null ? getNumberQuery(initializer) : null;
    }
    return null;
  }

  private static Integer handleStringConcatenation(BinaryExpressionTree expr) {
    Integer left = getNumberQuery(expr.leftOperand());
    Integer right = getNumberQuery(expr.rightOperand());
    return (left == null && right == null) ? null : (zeroIfNull(left) + zeroIfNull(right));
  }

  private static int zeroIfNull(@Nullable Integer intValue) {
    return intValue == null ? 0 : intValue;
  }

  @CheckForNull
  private static Integer countQuery(ExpressionTree expression) {
    return expression.is(Tree.Kind.STRING_LITERAL) ? StringUtils.countMatches(((LiteralTree) expression).value(), "?") : null;
  }
}
