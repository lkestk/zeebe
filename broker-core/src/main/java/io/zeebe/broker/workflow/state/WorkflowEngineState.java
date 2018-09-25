/*
 * Zeebe Broker Core
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.zeebe.broker.workflow.state;

import io.zeebe.broker.logstreams.processor.StreamProcessorLifecycleAware;
import io.zeebe.broker.logstreams.processor.TypedRecord;
import io.zeebe.broker.logstreams.processor.TypedStreamProcessor;
import io.zeebe.broker.workflow.processor.WorkflowInstanceLifecycle;
import io.zeebe.broker.workflow.processor.WorkflowInstanceMetrics;
import io.zeebe.broker.workflow.state.StoredRecord.Purpose;
import io.zeebe.logstreams.log.LogStream;
import io.zeebe.logstreams.processor.StreamProcessorContext;
import io.zeebe.protocol.impl.record.value.workflowinstance.WorkflowInstanceRecord;
import io.zeebe.protocol.intent.WorkflowInstanceIntent;
import io.zeebe.util.metrics.MetricsManager;

/*
 * Workflow Execution Concept:
 *
 * Processing concept:
 *
 * In: 1 event ---> BPMN step evaluation ---> Out: n events
 *
 * Data structures:
 *
 * - Index
 * - Events in log stream
 *
 * The index always contains the latest, materialized state of a workflow instance.
 * Events on the log stream have two purposes:
 *
 * - They represent changes to that state (=> that can be recorded via exporters)
 * - A state change can trigger other state changes, i.e. the workflow stream processor
 *   reacts to them. This allows us to break complex operations into smaller, atomic steps.
 *
 * In conclusion:
 *
 * - Whenever we process an event or command, we publish their effects as follow-up events.
 *   At the time of publication, the index already contains these effects.
 *
 * Index state:
 *
 * - In the index, we have two things:
 *   - element instances
 *   - tokens
 * - An element instance is an instance of a stateful BPMN element (e.g. service task, subprocess)
 * - A token is any event that is published to get from one element instance to another (e.g. sequence
 *   flow events, gateways). It is not explicitly represented as a "token event" or similar, there
 *   is no token identity.
 * - Element instances are explicitly represented in the index (e.g. to be able to cancel them),
 *   tokens are only counted (e.g. if there is still "something" going on, when we would
 *   like to complete a scope).
 * - Both things are transparently maintained in the index whenever an event is consumed or published.
 */
public class WorkflowEngineState implements StreamProcessorLifecycleAware {

  private final WorkflowState workflowState;
  private ElementInstanceState elementInstanceState;
  private WorkflowInstanceMetrics metrics;

  public WorkflowEngineState(WorkflowState workflowState) {
    this.workflowState = workflowState;
  }

  @Override
  public void onOpen(TypedStreamProcessor streamProcessor) {
    final StreamProcessorContext streamProcessorContext =
        streamProcessor.getStreamProcessorContext();
    final MetricsManager metricsManager =
        streamProcessorContext.getActorScheduler().getMetricsManager();
    final LogStream logStream = streamProcessorContext.getLogStream();

    this.metrics = new WorkflowInstanceMetrics(metricsManager, logStream.getPartitionId());
    this.elementInstanceState = workflowState.getElementInstanceState();
  }

  @Override
  public void onClose() {
    metrics.close();
  }

  public void onEventConsumed(TypedRecord<WorkflowInstanceRecord> record) {

    if (isFlowTriggeringEvent(record)) {
      final long scopeKey = record.getValue().getScopeInstanceKey();
      workflowState.getElementInstanceState().consumeToken(scopeKey);
    }
    // else: element instances remain in the index until a final state is published
  }

  private static boolean isFlowTriggeringEvent(TypedRecord<WorkflowInstanceRecord> record) {
    final WorkflowInstanceIntent state = (WorkflowInstanceIntent) record.getMetadata().getIntent();
    final long scopeKey = record.getValue().getScopeInstanceKey();

    return WorkflowInstanceLifecycle.isTokenState(state)
        || (WorkflowInstanceLifecycle.isFinalState(state) && scopeKey >= 0);
  }

  public void onEventProduced(
      long key, WorkflowInstanceIntent state, WorkflowInstanceRecord value) {
    if (WorkflowInstanceLifecycle.isElementInstanceState(state)) {
      onElementInstanceEventProduced(key, state, value);
    } else {
      onTokenEventProduced(key, state, value);
    }
  }

  public void deferEvent(TypedRecord<WorkflowInstanceRecord> event) {
    final long scopeKey = event.getValue().getScopeInstanceKey();
    // currently assuming only token events are deferred
    elementInstanceState.storeRecord(scopeKey, event, Purpose.DEFERRED_TOKEN);
    elementInstanceState.spawnToken(scopeKey); // the token remains active
  }

  public void storeFinishedToken(TypedRecord<WorkflowInstanceRecord> event) {
    final long scopeKey = event.getValue().getScopeInstanceKey();
    elementInstanceState.storeRecord(scopeKey, event, Purpose.FINISHED_TOKEN);
  }

  public void consumeStoredRecord(long scopeKey, long key, Purpose purpose) {
    elementInstanceState.removeStoredRecord(scopeKey, key, purpose);
    if (purpose == Purpose.DEFERRED_TOKEN) {
      elementInstanceState.consumeToken(scopeKey);
    }
  }

  private void onTokenEventProduced(
      long key, WorkflowInstanceIntent state, WorkflowInstanceRecord value) {
    final long scopeKey = value.getScopeInstanceKey();
    elementInstanceState.spawnToken(scopeKey);
  }

  private void onElementInstanceEventProduced(
      long key, WorkflowInstanceIntent state, WorkflowInstanceRecord value) {
    // only instances that have a multi-state lifecycle are represented in the index
    if (WorkflowInstanceLifecycle.isInitialState(state)) {
      final long scopeInstanceKey = value.getScopeInstanceKey();

      if (scopeInstanceKey >= 0) {
        final ElementInstance flowScopeInstance =
            elementInstanceState.getInstance(scopeInstanceKey);
        elementInstanceState.newInstance(flowScopeInstance, key, value, state);
      } else {
        elementInstanceState.newInstance(key, value, state);
      }
    } else if (WorkflowInstanceLifecycle.isFinalState(state)) {
      elementInstanceState.removeInstance(key);

      final long scopeInstanceKey = value.getScopeInstanceKey();

      // a final state is triggers continued execution, i.e. we count a new token
      if (scopeInstanceKey >= 0) // i.e. not root scope
      {
        elementInstanceState.spawnToken(scopeInstanceKey);
      }

    } else {
      final ElementInstance scopeInstance = elementInstanceState.getInstance(key);
      scopeInstance.setState(state);
      scopeInstance.setValue(value);
    }

    if (key == value.getWorkflowInstanceKey()) {
      if (state == WorkflowInstanceIntent.ELEMENT_TERMINATED) {
        metrics.countInstanceCanceled();
      } else if (state == WorkflowInstanceIntent.ELEMENT_COMPLETED) {
        metrics.countInstanceCompleted();
      }
    }
  }
}
