/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openpredict.exchange.biprocessor;

import com.lmax.disruptor.*;
import lombok.extern.slf4j.Slf4j;
import org.openpredict.exchange.beans.cmd.OrderCommand;
import org.openpredict.exchange.beans.cmd.OrderCommandType;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public final class GroupingProcessor implements EventProcessor {
    private static final int IDLE = 0;
    private static final int HALTED = IDLE + 1;
    private static final int RUNNING = HALTED + 1;

    private final AtomicInteger running = new AtomicInteger(IDLE);
    private final RingBuffer<OrderCommand> ringBuffer;
    private final SequenceBarrier sequenceBarrier;
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

    public GroupingProcessor(final RingBuffer<OrderCommand> ringBuffer, final SequenceBarrier sequenceBarrier) {
        this.ringBuffer = ringBuffer;
        this.sequenceBarrier = sequenceBarrier;
    }

    @Override
    public Sequence getSequence() {
        return sequence;
    }

    @Override
    public void halt() {
        running.set(HALTED);
        sequenceBarrier.alert();
    }

    @Override
    public boolean isRunning() {
        return running.get() != IDLE;
    }


    /**
     * It is ok to have another thread rerun this method after a halt().
     *
     * @throws IllegalStateException if this object instance is already running in a thread
     */
    @Override
    public void run() {
        if (running.compareAndSet(IDLE, RUNNING)) {
            sequenceBarrier.clearAlert();
            try {
                if (running.get() == RUNNING) {
                    processEvents();
                }
            } finally {
                running.set(IDLE);
            }
        } else {
            // This is a little bit of guess work.  The running state could of changed to HALTED by
            // this point.  However, Java does not have compareAndExchange which is the only way
            // to get it exactly correct.
            if (running.get() == RUNNING) {
                throw new IllegalStateException("Thread is already running");
            }
        }
    }

    private void processEvents() {
        long nextSequence = sequence.get() + 1L;

        long groupCounter = 0;
        long msgsInGroup = 0;

        long groupLastNs = 0;

        while (true) {
            try {

                // should spin and also check another barrier
                sequenceBarrier.checkAlert();
                long availableSequence = sequenceBarrier.tryWaitFor(nextSequence, 1000);

                if (nextSequence <= availableSequence) {
                    while (nextSequence <= availableSequence) {

                        OrderCommand cmd = ringBuffer.get(nextSequence);
                        nextSequence++;

                        cmd.eventsGroup = groupCounter;

                        if (cmd.command == OrderCommandType.NOP) {
                            // gust set next group and pass
                            continue;
                        }

                        msgsInGroup++;

                        // switch group after each N messages
                        if (msgsInGroup >= 192) {
                            groupCounter++;
                            msgsInGroup = 0;
                        }

                    }
                    sequence.set(availableSequence);
                    groupLastNs = System.nanoTime() + 1000;

                } else if (msgsInGroup > 0 && System.nanoTime() > groupLastNs) {
                    // switch group after T microseconds elapsed, if group is non empty
                    msgsInGroup = 0;
                    groupCounter++;
                }


            } catch (final TimeoutException e) {
                //
            } catch (final AlertException ex) {
                if (running.get() != RUNNING) {
                    break;
                }
            } catch (final Throwable ex) {
                sequence.set(nextSequence);
                nextSequence++;
            }
        }
    }
}