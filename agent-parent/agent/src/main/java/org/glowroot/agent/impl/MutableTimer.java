/*
 * Copyright 2014-2016 the original author or authors.
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
package org.glowroot.agent.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.google.common.collect.Lists;

import org.glowroot.agent.model.CommonTimerImpl;
import org.glowroot.wire.api.model.AggregateOuterClass.Aggregate;

class MutableTimer {

    private final String name;
    private final boolean extended;
    // aggregates use double instead of long to avoid (unlikely) 292 year nanosecond rollover
    private double totalNanos;
    private long count;
    private final List<MutableTimer> childTimers;

    static MutableTimer createRootTimer(String name, boolean extended) {
        return new MutableTimer(name, extended, 0, 0, new ArrayList<MutableTimer>());
    }

    private MutableTimer(String name, boolean extended, double totalNanos, long count,
            List<MutableTimer> nestedTimers) {
        this.name = name;
        this.extended = extended;
        this.totalNanos = totalNanos;
        this.count = count;
        this.childTimers = Lists.newArrayList(nestedTimers);
    }

    String getName() {
        return name;
    }

    void merge(CommonTimerImpl timer) {
        count += timer.getCount();
        totalNanos += timer.getTotalNanos();
        Iterator<? extends CommonTimerImpl> i = timer.getChildTimers();
        while (i.hasNext()) {
            CommonTimerImpl toBeMergedChildTimer = i.next();
            String toBeMergedChildTimerName = toBeMergedChildTimer.getName();
            boolean extended = toBeMergedChildTimer.isExtended();
            MutableTimer matchingChildTimer = null;
            for (MutableTimer childTimer : childTimers) {
                if (toBeMergedChildTimerName.equals(childTimer.getName())
                        && extended == childTimer.extended) {
                    matchingChildTimer = childTimer;
                    break;
                }
            }
            if (matchingChildTimer == null) {
                matchingChildTimer = new MutableTimer(toBeMergedChildTimer.getName(),
                        toBeMergedChildTimer.isExtended(), 0, 0, new ArrayList<MutableTimer>());
                childTimers.add(matchingChildTimer);
            }
            matchingChildTimer.merge(toBeMergedChildTimer);
        }
    }

    Aggregate.Timer toProto() {
        Aggregate.Timer.Builder builder = Aggregate.Timer.newBuilder()
                .setName(name)
                .setExtended(extended)
                .setTotalNanos(totalNanos)
                .setCount(count);
        for (MutableTimer childTimer : childTimers) {
            builder.addChildTimer(childTimer.toProto());
        }
        return builder.build();
    }
}
