/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.core.appender.rolling;

import java.util.Calendar;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;
import org.apache.logging.log4j.core.util.Integers;

/**
 * Rolls a file over based on time.
 */
@Plugin(name = "TimeBasedTriggeringPolicy", category = Core.CATEGORY_NAME, printObject = true)
public final class TimeBasedTriggeringPolicy extends AbstractTriggeringPolicy {


    public static class Builder implements org.apache.logging.log4j.core.util.Builder<TimeBasedTriggeringPolicy> {

        @PluginBuilderAttribute
        private int interval = 1;

        @PluginBuilderAttribute
        private boolean modulate = false;

        @PluginBuilderAttribute
        private int maxRandomDelay = 0;

        @Override
        public TimeBasedTriggeringPolicy build() {
            final long maxRandomDelayMillis = TimeUnit.SECONDS.toMillis(maxRandomDelay);
            return new TimeBasedTriggeringPolicy(interval, modulate, maxRandomDelayMillis);
        }

        public int getInterval() {
            return interval;
        }

        public boolean isModulate() {
            return modulate;
        }

        public int getMaxRandomDelay() {
            return maxRandomDelay;
        }

        public Builder withInterval(final int interval){
            this.interval = interval;
            return this;
        }

        public Builder withModulate(final boolean modulate){
            this.modulate = modulate;
            return this;
        }

        public Builder withMaxRandomDelay(final int maxRandomDelay){
            this.maxRandomDelay = maxRandomDelay;
            return this;
        }

    }

    private long nextRolloverMillis;
    private final int interval;
    private final boolean modulate;
    private final long maxRandomDelayMillis;

    private RollingFileManager manager;

    //TODO needed for now achieve similar behavior
    private long nextFileTime = 0;

    private TimeBasedTriggeringPolicy(final int interval, final boolean modulate, final long maxRandomDelayMillis) {
        this.interval = interval;
        this.modulate = modulate;
        this.maxRandomDelayMillis = maxRandomDelayMillis;
    }

    public int getInterval() {
        return interval;
    }

    public long getNextRolloverMillis() {
        return nextRolloverMillis;
    }

    /**
     * Initializes the policy.
     * @param aManager The RollingFileManager.
     */
    @Override
    public void initialize(final RollingFileManager aManager) {
        this.manager = aManager;
        long current = aManager.getFileTime();
        if (current == 0) {
            current = System.currentTimeMillis();
        }

        nextRolloverMillis = ThreadLocalRandom.current().nextLong(0, 1 + maxRandomDelayMillis)
                + getNextTime(current, interval, modulate, aManager.getPatternProcessor().getFrequency());
        // previously two calls were done, because the intention was always to set the calculated time into prevFileTime
        aManager.getPatternProcessor().setPrevFileTime(getNextTime(current, interval, modulate, aManager.getPatternProcessor().getFrequency()));
    }

    /**
     * Determines whether a rollover should occur.
     * @param event   A reference to the currently event.
     * @return true if a rollover should occur.
     */
    @Override
    public boolean isTriggeringEvent(final LogEvent event) {
        final long nowMillis = event.getTimeMillis();
        if (nowMillis >= nextRolloverMillis) {
            manager.getPatternProcessor().setPrevFileTime(nextFileTime);
            nextRolloverMillis = ThreadLocalRandom.current().nextLong(0, 1 + maxRandomDelayMillis)
                    + getNextTime(nowMillis, interval, modulate, manager.getPatternProcessor().getFrequency());
            manager.getPatternProcessor().setCurrentFileTime(System.currentTimeMillis());
            return true;
        }
        return false;
    }

    /**
     * Returns the next potential rollover time.
     * @param currentMillis The current time.
     * @param increment The increment to the next time.
     * @param modulus If true the time will be rounded to occur on a boundary aligned with the increment.
     * @return the next potential rollover time and the timestamp for the target file.
     */
    private long getNextTime(final long currentMillis, final int increment, final boolean modulus, final RolloverFrequency frequency) {
        if (frequency == null) {
            throw new IllegalStateException("Pattern does not contain a date");
        }
        long nextTime;
        final Calendar currentCal = Calendar.getInstance();
        currentCal.setTimeInMillis(currentMillis);
        final Calendar cal = Calendar.getInstance();
        currentCal.setMinimalDaysInFirstWeek(7);
        cal.setMinimalDaysInFirstWeek(7);
        cal.set(currentCal.get(Calendar.YEAR), 0, 1, 0, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (frequency == RolloverFrequency.ANNUALLY) {
            increment(cal, Calendar.YEAR, increment, modulus);
            nextTime = cal.getTimeInMillis();
            cal.add(Calendar.YEAR, -1);
            return nextTime;
        }
        cal.set(Calendar.MONTH, currentCal.get(Calendar.MONTH));
        if (frequency == RolloverFrequency.MONTHLY) {
            increment(cal, Calendar.MONTH, increment, modulus);
            nextTime = cal.getTimeInMillis();
            cal.add(Calendar.MONTH, -1);
            nextFileTime = cal.getTimeInMillis();
            return nextTime;
        }
        if (frequency == RolloverFrequency.WEEKLY) {
            cal.set(Calendar.WEEK_OF_YEAR, currentCal.get(Calendar.WEEK_OF_YEAR));
            increment(cal, Calendar.WEEK_OF_YEAR, increment, modulus);
            cal.set(Calendar.DAY_OF_WEEK, currentCal.getFirstDayOfWeek());
            nextTime = cal.getTimeInMillis();
            cal.add(Calendar.WEEK_OF_YEAR, -1);
            nextFileTime = cal.getTimeInMillis();
            return nextTime;
        }
        cal.set(Calendar.DAY_OF_YEAR, currentCal.get(Calendar.DAY_OF_YEAR));
        if (frequency == RolloverFrequency.DAILY) {
            increment(cal, Calendar.DAY_OF_YEAR, increment, modulus);
            nextTime = cal.getTimeInMillis();
            cal.add(Calendar.DAY_OF_YEAR, -1);
            nextFileTime = cal.getTimeInMillis();
            return nextTime;
        }
        cal.set(Calendar.HOUR_OF_DAY, currentCal.get(Calendar.HOUR_OF_DAY));
        if (frequency == RolloverFrequency.HOURLY) {
            increment(cal, Calendar.HOUR_OF_DAY, increment, modulus);
            nextTime = cal.getTimeInMillis();
            cal.add(Calendar.HOUR_OF_DAY, -1);
            nextFileTime = cal.getTimeInMillis();
            return nextTime;
        }
        cal.set(Calendar.MINUTE, currentCal.get(Calendar.MINUTE));
        if (frequency == RolloverFrequency.EVERY_MINUTE) {
            increment(cal, Calendar.MINUTE, increment, modulus);
            nextTime = cal.getTimeInMillis();
            cal.add(Calendar.MINUTE, -1);
            nextFileTime = cal.getTimeInMillis();
            return nextTime;
        }
        cal.set(Calendar.SECOND, currentCal.get(Calendar.SECOND));
        if (frequency == RolloverFrequency.EVERY_SECOND) {
            increment(cal, Calendar.SECOND, increment, modulus);
            nextTime = cal.getTimeInMillis();
            cal.add(Calendar.SECOND, -1);
            nextFileTime = cal.getTimeInMillis();
            return nextTime;
        }
        cal.set(Calendar.MILLISECOND, currentCal.get(Calendar.MILLISECOND));
        increment(cal, Calendar.MILLISECOND, increment, modulus);
        nextTime = cal.getTimeInMillis();
        cal.add(Calendar.MILLISECOND, -1);
        nextFileTime = cal.getTimeInMillis();
        return nextTime;
    }

    private void increment(final Calendar cal, final int type, final int increment, final boolean modulate) {
        final int interval =  modulate ? increment - (cal.get(type) % increment) : increment;
        cal.add(type, interval);
    }

    /**
     * Creates a TimeBasedTriggeringPolicy.
     * @param interval The interval between rollovers.
     * @param modulate If true the time will be rounded to occur on a boundary aligned with the increment.
     * @return a TimeBasedTriggeringPolicy.
     * @deprecated Use {@link #newBuilder()}.
     */
    @Deprecated
    public static TimeBasedTriggeringPolicy createPolicy(
            @PluginAttribute("interval") final String interval,
            @PluginAttribute("modulate") final String modulate) {
        return newBuilder()
                .withInterval(Integers.parseInt(interval, 1))
                .withModulate(Boolean.parseBoolean(modulate))
                .build();
    }

    @PluginBuilderFactory
    public static TimeBasedTriggeringPolicy.Builder newBuilder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "TimeBasedTriggeringPolicy(nextRolloverMillis=" + nextRolloverMillis + ", interval=" + interval
                + ", modulate=" + modulate + ")";
    }

}
