/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.query.executor;

import org.apache.iotdb.db.exception.StorageEngineException;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.qp.physical.crud.*;
import org.apache.iotdb.db.query.context.QueryContext;
import org.apache.iotdb.db.query.dataset.groupby.GroupByEngineDataSet;
import org.apache.iotdb.db.query.dataset.groupby.GroupByFillDataSet;
import org.apache.iotdb.db.query.dataset.groupby.GroupByWithValueFilterDataSet;
import org.apache.iotdb.db.query.dataset.groupby.GroupByWithoutValueFilterDataSet;
import org.apache.iotdb.db.query.fill.IFill;
import org.apache.iotdb.tsfile.exception.filter.QueryFilterOptimizationException;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.read.expression.ExpressionType;
import org.apache.iotdb.tsfile.read.expression.IExpression;
import org.apache.iotdb.tsfile.read.expression.impl.BinaryExpression;
import org.apache.iotdb.tsfile.read.expression.impl.GlobalTimeExpression;
import org.apache.iotdb.tsfile.read.expression.util.ExpressionOptimizer;
import org.apache.iotdb.tsfile.read.filter.GroupByFilter;
import org.apache.iotdb.tsfile.read.query.dataset.QueryDataSet;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Query entrance class of IoTDB query process. All query clause will be transformed to physical
 * plan, physical plan will be executed by EngineQueryRouter.
 */
public class QueryRouter implements IQueryRouter {

  @Override
  public QueryDataSet rawDataQuery(RawDataQueryPlan queryPlan, QueryContext context)
      throws StorageEngineException, QueryProcessException {
    IExpression expression = queryPlan.getExpression();
    List<Path> deduplicatedPaths = queryPlan.getDeduplicatedPaths();

    IExpression optimizedExpression;
    try {
      optimizedExpression = expression == null ? null : ExpressionOptimizer.getInstance()
          .optimize(expression, deduplicatedPaths);
    } catch (QueryFilterOptimizationException e) {
      throw new StorageEngineException(e.getMessage());
    }
    queryPlan.setExpression(optimizedExpression);

    RawDataQueryExecutor rawDataQueryExecutor = getRawDataQueryExecutor(queryPlan);

    if (!queryPlan.isAlignByTime()) {
      return rawDataQueryExecutor.executeNonAlign(context, queryPlan);
    }

    if (optimizedExpression != null
        && optimizedExpression.getType() != ExpressionType.GLOBAL_TIME) {
      return rawDataQueryExecutor.executeWithValueFilter(context, queryPlan);

    }
    return rawDataQueryExecutor.executeWithoutValueFilter(context, queryPlan);
  }

  protected RawDataQueryExecutor getRawDataQueryExecutor(RawDataQueryPlan queryPlan) {
    return new RawDataQueryExecutor(queryPlan);
  }

  @Override
  public QueryDataSet aggregate(AggregationPlan aggregationPlan, QueryContext context)
      throws QueryFilterOptimizationException, StorageEngineException, QueryProcessException,
      IOException {

    IExpression expression = aggregationPlan.getExpression();
    List<Path> deduplicatedPaths = aggregationPlan.getDeduplicatedPaths();

    // optimize expression to an executable one
    IExpression optimizedExpression =
        expression == null ? null :
            ExpressionOptimizer.getInstance().optimize(expression, deduplicatedPaths);

    aggregationPlan.setExpression(optimizedExpression);

    AggregationExecutor engineExecutor = getAggregationExecutor(aggregationPlan);

    if (optimizedExpression != null
        && optimizedExpression.getType() != ExpressionType.GLOBAL_TIME) {
      return engineExecutor.executeWithValueFilter(context, aggregationPlan);
    }

    return engineExecutor.executeWithoutValueFilter(context, aggregationPlan);
  }

  protected AggregationExecutor getAggregationExecutor(AggregationPlan aggregationPlan) {
    return new AggregationExecutor(aggregationPlan);
  }

  @Override
  public QueryDataSet groupBy(GroupByPlan groupByPlan, QueryContext context)
      throws QueryFilterOptimizationException, StorageEngineException, QueryProcessException {
    long unit = groupByPlan.getInterval();
    long slidingStep = groupByPlan.getSlidingStep();
    long startTime = groupByPlan.getStartTime();
    long endTime = groupByPlan.getEndTime();

    IExpression expression = groupByPlan.getExpression();
    List<Path> selectedSeries = groupByPlan.getDeduplicatedPaths();

    GlobalTimeExpression timeExpression = new GlobalTimeExpression(
        new GroupByFilter(unit, slidingStep, startTime, endTime));

    if (expression == null) {
      expression = timeExpression;
    } else {
      expression = BinaryExpression.and(expression, timeExpression);
    }

    // optimize expression to an executable one
    IExpression optimizedExpression = ExpressionOptimizer.getInstance()
        .optimize(expression, selectedSeries);
    groupByPlan.setExpression(optimizedExpression);

    if (optimizedExpression.getType() == ExpressionType.GLOBAL_TIME) {
      return getGroupByWithoutValueFilterDataSet(context, groupByPlan);
    } else {
      return getGroupByWithValueFilterDataSet(context, groupByPlan);
    }
  }

  protected GroupByWithoutValueFilterDataSet getGroupByWithoutValueFilterDataSet(QueryContext context, GroupByPlan plan)
      throws StorageEngineException, QueryProcessException {
    return new GroupByWithoutValueFilterDataSet(context, plan);
  }

  protected GroupByWithValueFilterDataSet getGroupByWithValueFilterDataSet(QueryContext context, GroupByPlan plan)
      throws StorageEngineException, QueryProcessException {
    return new GroupByWithValueFilterDataSet(context, plan);
  }

  @Override
  public QueryDataSet fill(FillQueryPlan fillQueryPlan, QueryContext context)
      throws StorageEngineException, QueryProcessException, IOException {
    List<Path> fillPaths = fillQueryPlan.getDeduplicatedPaths();
    List<TSDataType> dataTypes = fillQueryPlan.getDeduplicatedDataTypes();
    long queryTime = fillQueryPlan.getQueryTime();
    Map<TSDataType, IFill> fillType = fillQueryPlan.getFillType();

    FillQueryExecutor fillQueryExecutor = getFillExecutor(fillPaths, dataTypes, queryTime,
        fillType);
    return fillQueryExecutor.execute(context, fillQueryPlan);
  }

  protected FillQueryExecutor getFillExecutor(
      List<Path> fillPaths,
      List<TSDataType> dataTypes, long queryTime,
      Map<TSDataType, IFill> fillType) {
    return new FillQueryExecutor(fillPaths, dataTypes, queryTime, fillType);
  }

  @Override
  public QueryDataSet groupByFill(GroupByFillPlan groupByFillPlan, QueryContext context)
          throws QueryFilterOptimizationException, StorageEngineException, QueryProcessException, IOException {
    GroupByEngineDataSet groupByEngineDataSet = (GroupByEngineDataSet) groupBy(groupByFillPlan, context);
    return new GroupByFillDataSet(groupByFillPlan.getDeduplicatedPaths(), groupByFillPlan.getDeduplicatedDataTypes(),
            groupByEngineDataSet, groupByFillPlan.getFillType(), context, groupByFillPlan);
  }

  @Override
  public QueryDataSet lastQuery(LastQueryPlan lastQueryPlan, QueryContext context)
          throws StorageEngineException, QueryProcessException, IOException {
    LastQueryExecutor lastQueryExecutor = new LastQueryExecutor(lastQueryPlan);
    return lastQueryExecutor.execute(context, lastQueryPlan);
  }

}
