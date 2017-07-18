/**
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

package org.apache.hadoop.hive.metastore.hbase.stats;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.hive.common.ndv.NumDistinctValueEstimator;
import org.apache.hadoop.hive.common.ndv.NumDistinctValueEstimatorFactory;
import org.apache.hadoop.hive.metastore.StatObjectConverter;
import org.apache.hadoop.hive.metastore.api.ColumnStatistics;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsData;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsObj;
import org.apache.hadoop.hive.metastore.api.DecimalColumnStatsData;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.hbase.HBaseUtils;

public class DecimalColumnStatsAggregator extends ColumnStatsAggregator implements
    IExtrapolatePartStatus {

  @Override
  public ColumnStatisticsObj aggregate(String colName, List<String> partNames,
      List<ColumnStatistics> css) throws MetaException {
    ColumnStatisticsObj statsObj = null;

    // check if all the ColumnStatisticsObjs contain stats and all the ndv are
    // bitvectors
    boolean doAllPartitionContainStats = partNames.size() == css.size();
    NumDistinctValueEstimator ndvEstimator = null;
    String colType = null;
    for (ColumnStatistics cs : css) {
      if (cs.getStatsObjSize() != 1) {
        throw new MetaException(
            "The number of columns should be exactly one in aggrStats, but found "
                + cs.getStatsObjSize());
      }
      ColumnStatisticsObj cso = cs.getStatsObjIterator().next();
      if (statsObj == null) {
        colType = cso.getColType();
        statsObj = ColumnStatsAggregatorFactory.newColumnStaticsObj(colName, colType, cso
            .getStatsData().getSetField());
      }
      if (!cso.getStatsData().getDecimalStats().isSetBitVectors()
          || cso.getStatsData().getDecimalStats().getBitVectors().length() == 0) {
        ndvEstimator = null;
        break;
      } else {
        // check if all of the bit vectors can merge
        NumDistinctValueEstimator estimator = NumDistinctValueEstimatorFactory
            .getNumDistinctValueEstimator(cso.getStatsData().getDecimalStats().getBitVectors());
        if (ndvEstimator == null) {
          ndvEstimator = estimator;
        } else {
          if (ndvEstimator.canMerge(estimator)) {
            continue;
          } else {
            ndvEstimator = null;
            break;
          }
        }
      }
    }
    if (ndvEstimator != null) {
      ndvEstimator = NumDistinctValueEstimatorFactory
          .getEmptyNumDistinctValueEstimator(ndvEstimator);
    }
    ColumnStatisticsData columnStatisticsData = new ColumnStatisticsData();
    if (doAllPartitionContainStats || css.size() < 2) {
      DecimalColumnStatsData aggregateData = null;
      long lowerBound = 0;
      long higherBound = 0;
      double densityAvgSum = 0.0;
      for (ColumnStatistics cs : css) {
        ColumnStatisticsObj cso = cs.getStatsObjIterator().next();
        DecimalColumnStatsData newData = cso.getStatsData().getDecimalStats();
        if (useDensityFunctionForNDVEstimation) {
          lowerBound = Math.max(lowerBound, newData.getNumDVs());
          higherBound += newData.getNumDVs();
          densityAvgSum += (HBaseUtils.getDoubleValue(newData.getHighValue()) - HBaseUtils
              .getDoubleValue(newData.getLowValue())) / newData.getNumDVs();
        }
        if (ndvEstimator != null) {
          ndvEstimator.mergeEstimators(NumDistinctValueEstimatorFactory
              .getNumDistinctValueEstimator(newData.getBitVectors()));
        }
        if (aggregateData == null) {
          aggregateData = newData.deepCopy();
        } else {
          if (HBaseUtils.getDoubleValue(aggregateData.getLowValue()) < HBaseUtils
              .getDoubleValue(newData.getLowValue())) {
            aggregateData.setLowValue(aggregateData.getLowValue());
          } else {
            aggregateData.setLowValue(newData.getLowValue());
          }
          if (HBaseUtils.getDoubleValue(aggregateData.getHighValue()) > HBaseUtils
              .getDoubleValue(newData.getHighValue())) {
            aggregateData.setHighValue(aggregateData.getHighValue());
          } else {
            aggregateData.setHighValue(newData.getHighValue());
          }
          aggregateData.setNumNulls(aggregateData.getNumNulls() + newData.getNumNulls());
          aggregateData.setNumDVs(Math.max(aggregateData.getNumDVs(), newData.getNumDVs()));
        }
      }
      if (ndvEstimator != null) {
        // if all the ColumnStatisticsObjs contain bitvectors, we do not need to
        // use uniform distribution assumption because we can merge bitvectors
        // to get a good estimation.
        aggregateData.setNumDVs(ndvEstimator.estimateNumDistinctValues());
      } else {
        if (useDensityFunctionForNDVEstimation) {
          // We have estimation, lowerbound and higherbound. We use estimation
          // if it is between lowerbound and higherbound.
          double densityAvg = densityAvgSum / partNames.size();
          long estimation = (long) ((HBaseUtils.getDoubleValue(aggregateData.getHighValue()) - HBaseUtils
              .getDoubleValue(aggregateData.getLowValue())) / densityAvg);
          if (estimation < lowerBound) {
            aggregateData.setNumDVs(lowerBound);
          } else if (estimation > higherBound) {
            aggregateData.setNumDVs(higherBound);
          } else {
            aggregateData.setNumDVs(estimation);
          }
        } else {
          // Without useDensityFunctionForNDVEstimation, we just use the
          // default one, which is the max of all the partitions and it is
          // already done.
        }
      }
      columnStatisticsData.setDecimalStats(aggregateData);
    } else {
      // we need extrapolation
      Map<String, Integer> indexMap = new HashMap<String, Integer>();
      for (int index = 0; index < partNames.size(); index++) {
        indexMap.put(partNames.get(index), index);
      }
      Map<String, Double> adjustedIndexMap = new HashMap<String, Double>();
      Map<String, ColumnStatisticsData> adjustedStatsMap = new HashMap<String, ColumnStatisticsData>();
      // while we scan the css, we also get the densityAvg, lowerbound and
      // higerbound when useDensityFunctionForNDVEstimation is true.
      double densityAvgSum = 0.0;
      if (ndvEstimator == null) {
        // if not every partition uses bitvector for ndv, we just fall back to
        // the traditional extrapolation methods.
        for (ColumnStatistics cs : css) {
          String partName = cs.getStatsDesc().getPartName();
          ColumnStatisticsObj cso = cs.getStatsObjIterator().next();
          DecimalColumnStatsData newData = cso.getStatsData().getDecimalStats();
          if (useDensityFunctionForNDVEstimation) {
            densityAvgSum += (HBaseUtils.getDoubleValue(newData.getHighValue()) - HBaseUtils
                .getDoubleValue(newData.getLowValue())) / newData.getNumDVs();
          }
          adjustedIndexMap.put(partName, (double) indexMap.get(partName));
          adjustedStatsMap.put(partName, cso.getStatsData());
        }
      } else {
        // we first merge all the adjacent bitvectors that we could merge and
        // derive new partition names and index.
        StringBuilder pseudoPartName = new StringBuilder();
        double pseudoIndexSum = 0;
        int length = 0;
        int curIndex = -1;
        DecimalColumnStatsData aggregateData = null;
        for (ColumnStatistics cs : css) {
          String partName = cs.getStatsDesc().getPartName();
          ColumnStatisticsObj cso = cs.getStatsObjIterator().next();
          DecimalColumnStatsData newData = cso.getStatsData().getDecimalStats();
          // newData.isSetBitVectors() should be true for sure because we
          // already checked it before.
          if (indexMap.get(partName) != curIndex) {
            // There is bitvector, but it is not adjacent to the previous ones.
            if (length > 0) {
              // we have to set ndv
              adjustedIndexMap.put(pseudoPartName.toString(), pseudoIndexSum / length);
              aggregateData.setNumDVs(ndvEstimator.estimateNumDistinctValues());
              ColumnStatisticsData csd = new ColumnStatisticsData();
              csd.setDecimalStats(aggregateData);
              adjustedStatsMap.put(pseudoPartName.toString(), csd);
              if (useDensityFunctionForNDVEstimation) {
                densityAvgSum += (HBaseUtils.getDoubleValue(aggregateData.getHighValue()) - HBaseUtils
                    .getDoubleValue(aggregateData.getLowValue())) / aggregateData.getNumDVs();
              }
              // reset everything
              pseudoPartName = new StringBuilder();
              pseudoIndexSum = 0;
              length = 0;
              ndvEstimator = NumDistinctValueEstimatorFactory.getEmptyNumDistinctValueEstimator(ndvEstimator);
            }
            aggregateData = null;
          }
          curIndex = indexMap.get(partName);
          pseudoPartName.append(partName);
          pseudoIndexSum += curIndex;
          length++;
          curIndex++;
          if (aggregateData == null) {
            aggregateData = newData.deepCopy();
          } else {
            if (HBaseUtils.getDoubleValue(aggregateData.getLowValue()) < HBaseUtils
                .getDoubleValue(newData.getLowValue())) {
              aggregateData.setLowValue(aggregateData.getLowValue());
            } else {
              aggregateData.setLowValue(newData.getLowValue());
            }
            if (HBaseUtils.getDoubleValue(aggregateData.getHighValue()) > HBaseUtils
                .getDoubleValue(newData.getHighValue())) {
              aggregateData.setHighValue(aggregateData.getHighValue());
            } else {
              aggregateData.setHighValue(newData.getHighValue());
            }
            aggregateData.setNumNulls(aggregateData.getNumNulls() + newData.getNumNulls());
          }
          ndvEstimator.mergeEstimators(NumDistinctValueEstimatorFactory
              .getNumDistinctValueEstimator(newData.getBitVectors()));
        }
        if (length > 0) {
          // we have to set ndv
          adjustedIndexMap.put(pseudoPartName.toString(), pseudoIndexSum / length);
          aggregateData.setNumDVs(ndvEstimator.estimateNumDistinctValues());
          ColumnStatisticsData csd = new ColumnStatisticsData();
          csd.setDecimalStats(aggregateData);
          adjustedStatsMap.put(pseudoPartName.toString(), csd);
          if (useDensityFunctionForNDVEstimation) {
            densityAvgSum += (HBaseUtils.getDoubleValue(aggregateData.getHighValue()) - HBaseUtils
                .getDoubleValue(aggregateData.getLowValue())) / aggregateData.getNumDVs();
          }
        }
      }
      extrapolate(columnStatisticsData, partNames.size(), css.size(), adjustedIndexMap,
          adjustedStatsMap, densityAvgSum / adjustedStatsMap.size());
    }
    statsObj.setStatsData(columnStatisticsData);
    return statsObj;
  }

  @Override
  public void extrapolate(ColumnStatisticsData extrapolateData, int numParts,
      int numPartsWithStats, Map<String, Double> adjustedIndexMap,
      Map<String, ColumnStatisticsData> adjustedStatsMap, double densityAvg) {
    int rightBorderInd = numParts;
    DecimalColumnStatsData extrapolateDecimalData = new DecimalColumnStatsData();
    Map<String, DecimalColumnStatsData> extractedAdjustedStatsMap = new HashMap<>();
    for (Map.Entry<String, ColumnStatisticsData> entry : adjustedStatsMap.entrySet()) {
      extractedAdjustedStatsMap.put(entry.getKey(), entry.getValue().getDecimalStats());
    }
    List<Map.Entry<String, DecimalColumnStatsData>> list = new LinkedList<Map.Entry<String, DecimalColumnStatsData>>(
        extractedAdjustedStatsMap.entrySet());
    // get the lowValue
    Collections.sort(list, new Comparator<Map.Entry<String, DecimalColumnStatsData>>() {
      public int compare(Map.Entry<String, DecimalColumnStatsData> o1,
          Map.Entry<String, DecimalColumnStatsData> o2) {
        return o1.getValue().getLowValue().compareTo(o2.getValue().getLowValue());
      }
    });
    double minInd = adjustedIndexMap.get(list.get(0).getKey());
    double maxInd = adjustedIndexMap.get(list.get(list.size() - 1).getKey());
    double lowValue = 0;
    double min = HBaseUtils.getDoubleValue(list.get(0).getValue().getLowValue());
    double max = HBaseUtils.getDoubleValue(list.get(list.size() - 1).getValue().getLowValue());
    if (minInd == maxInd) {
      lowValue = min;
    } else if (minInd < maxInd) {
      // left border is the min
      lowValue = (max - (max - min) * maxInd / (maxInd - minInd));
    } else {
      // right border is the min
      lowValue = (max - (max - min) * (rightBorderInd - maxInd) / (minInd - maxInd));
    }

    // get the highValue
    Collections.sort(list, new Comparator<Map.Entry<String, DecimalColumnStatsData>>() {
      public int compare(Map.Entry<String, DecimalColumnStatsData> o1,
          Map.Entry<String, DecimalColumnStatsData> o2) {
        return o1.getValue().getHighValue().compareTo(o2.getValue().getHighValue());
      }
    });
    minInd = adjustedIndexMap.get(list.get(0).getKey());
    maxInd = adjustedIndexMap.get(list.get(list.size() - 1).getKey());
    double highValue = 0;
    min = HBaseUtils.getDoubleValue(list.get(0).getValue().getHighValue());
    max = HBaseUtils.getDoubleValue(list.get(list.size() - 1).getValue().getHighValue());
    if (minInd == maxInd) {
      highValue = min;
    } else if (minInd < maxInd) {
      // right border is the max
      highValue = (min + (max - min) * (rightBorderInd - minInd) / (maxInd - minInd));
    } else {
      // left border is the max
      highValue = (min + (max - min) * minInd / (minInd - maxInd));
    }

    // get the #nulls
    long numNulls = 0;
    for (Map.Entry<String, DecimalColumnStatsData> entry : extractedAdjustedStatsMap.entrySet()) {
      numNulls += entry.getValue().getNumNulls();
    }
    // we scale up sumNulls based on the number of partitions
    numNulls = numNulls * numParts / numPartsWithStats;

    // get the ndv
    long ndv = 0;
    long ndvMin = 0;
    long ndvMax = 0;
    Collections.sort(list, new Comparator<Map.Entry<String, DecimalColumnStatsData>>() {
      public int compare(Map.Entry<String, DecimalColumnStatsData> o1,
          Map.Entry<String, DecimalColumnStatsData> o2) {
        return o1.getValue().getNumDVs() < o2.getValue().getNumDVs() ? -1 : 1;
      }
    });
    long lowerBound = list.get(list.size() - 1).getValue().getNumDVs();
    long higherBound = 0;
    for (Map.Entry<String, DecimalColumnStatsData> entry : list) {
      higherBound += entry.getValue().getNumDVs();
    }
    if (useDensityFunctionForNDVEstimation && densityAvg != 0.0) {
      ndv = (long) ((highValue - lowValue) / densityAvg);
      if (ndv < lowerBound) {
        ndv = lowerBound;
      } else if (ndv > higherBound) {
        ndv = higherBound;
      }
    } else {
      minInd = adjustedIndexMap.get(list.get(0).getKey());
      maxInd = adjustedIndexMap.get(list.get(list.size() - 1).getKey());
      ndvMin = list.get(0).getValue().getNumDVs();
      ndvMax = list.get(list.size() - 1).getValue().getNumDVs();
      if (minInd == maxInd) {
        ndv = ndvMin;
      } else if (minInd < maxInd) {
        // right border is the max
        ndv = (long) (ndvMin + (ndvMax - ndvMin) * (rightBorderInd - minInd) / (maxInd - minInd));
      } else {
        // left border is the max
        ndv = (long) (ndvMin + (ndvMax - ndvMin) * minInd / (minInd - maxInd));
      }
    }
    extrapolateDecimalData.setLowValue(StatObjectConverter.createThriftDecimal(String
        .valueOf(lowValue)));
    extrapolateDecimalData.setHighValue(StatObjectConverter.createThriftDecimal(String
        .valueOf(highValue)));
    extrapolateDecimalData.setNumNulls(numNulls);
    extrapolateDecimalData.setNumDVs(ndv);
    extrapolateData.setDecimalStats(extrapolateDecimalData);
  }
}
