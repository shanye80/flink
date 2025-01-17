/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.operators.lifecycle.validation;

import org.apache.flink.runtime.OperatorIDPair;
import org.apache.flink.runtime.jobgraph.JobVertex;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.operators.lifecycle.TestJobWithDescription;
import org.apache.flink.runtime.operators.lifecycle.event.InputEndedEvent;
import org.apache.flink.runtime.operators.lifecycle.event.TestEvent;
import org.apache.flink.runtime.operators.lifecycle.event.WatermarkReceivedEvent;
import org.apache.flink.streaming.api.watermark.Watermark;

import java.util.BitSet;
import java.util.List;
import java.util.NoSuchElementException;

import static java.lang.String.format;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * For each input, checks that the {@link Watermark#MAX_WATERMARK} was received and then the input
 * was closed.
 */
public class DrainingValidator implements TestOperatorLifecycleValidator {

    @Override
    public void validateOperatorLifecycle(
            TestJobWithDescription job,
            String operatorId,
            int subtaskIndex,
            List<TestEvent> operatorEvents) {
        BitSet endedInputs = new BitSet();
        BitSet inputsWithMaxWatermark = new BitSet();
        for (TestEvent ev : operatorEvents) {
            if (ev instanceof WatermarkReceivedEvent) {
                WatermarkReceivedEvent w = (WatermarkReceivedEvent) ev;
                if (w.ts == Watermark.MAX_WATERMARK.getTimestamp()) {
                    assertFalse(inputsWithMaxWatermark.get(w.inputId));
                    inputsWithMaxWatermark.set(w.inputId);
                }
            } else if (ev instanceof InputEndedEvent) {
                InputEndedEvent w = (InputEndedEvent) ev;
                assertTrue(
                        format(
                                "Input %d ended before receiving max watermark by %s[%d]",
                                w.inputId, operatorId, subtaskIndex),
                        inputsWithMaxWatermark.get(w.inputId));
                assertFalse(endedInputs.get(w.inputId));
                endedInputs.set(w.inputId);
            }
        }
        assertEquals(
                format("Incorrect number of ended inputs for %s[%d]", operatorId, subtaskIndex),
                getNumInputs(job, operatorId),
                endedInputs.cardinality());
    }

    private static int getNumInputs(TestJobWithDescription testJob, String operator) {
        Integer explicitNumInputs = testJob.operatorsNumberOfInputs.get(operator);
        if (explicitNumInputs != null) {
            return explicitNumInputs;
        }
        Iterable<JobVertex> vertices = testJob.jobGraph.getVertices();
        for (JobVertex vertex : vertices) {
            for (OperatorIDPair p : vertex.getOperatorIDs()) {
                OperatorID operatorID =
                        p.getUserDefinedOperatorID().orElse(p.getGeneratedOperatorID());
                if (operatorID.toString().equals(operator)) {
                    // warn: this returns the number of network inputs
                    // which may not coincide with logical
                    // e.g. single-input operator after two sources united
                    // will have two network inputs
                    return vertex.getNumberOfInputs();
                }
            }
        }
        throw new NoSuchElementException(operator);
    }
}
