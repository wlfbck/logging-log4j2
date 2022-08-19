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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.lookup.StrSubstitutor;
import org.apache.logging.log4j.core.pattern.ArrayPatternConverter;
import org.apache.logging.log4j.core.pattern.DatePatternConverter;
import org.apache.logging.log4j.core.pattern.FormattingInfo;
import org.apache.logging.log4j.core.pattern.PatternConverter;
import org.apache.logging.log4j.core.pattern.PatternParser;
import org.apache.logging.log4j.status.StatusLogger;

/**
 * Parses the rollover pattern.
 */
public class PatternProcessor {

    protected static final Logger LOGGER = StatusLogger.getLogger();
    private static final String KEY = "FileConverter";

    private static final char YEAR_CHAR = 'y';
    private static final char MONTH_CHAR = 'M';
    private static final char[] WEEK_CHARS = {'w', 'W'};
    private static final char[] DAY_CHARS = {'D', 'd', 'F', 'E'};
    private static final char[] HOUR_CHARS = {'H', 'K', 'h', 'k'};
    private static final char MINUTE_CHAR = 'm';
    private static final char SECOND_CHAR = 's';
    private static final char MILLIS_CHAR = 'S';

    private final ArrayPatternConverter[] patternConverters;
    private final FormattingInfo[] patternFields;
    private final FileExtension fileExtension;

    //TODO: I would suggest renaming this to "startTimeOfLastFile". Seems more fitting.
    private long prevFileTime = 0;

    //TODO: I would suggest renaming this to "endTimeOfLastFile". Seems more fitting.
    private long currentFileTime = 0;

    private RolloverFrequency frequency = null;

    private final String pattern;

    public String getPattern() {
        return pattern;
    }

    @Override
    public String toString() {
        return pattern;
    }

    /**
     * Constructor.
     * @param pattern The file pattern.
     */
    public PatternProcessor(final String pattern) {
        this.pattern = pattern;
        final PatternParser parser = createPatternParser();
        final List<PatternConverter> converters = new ArrayList<>();
        final List<FormattingInfo> fields = new ArrayList<>();
        parser.parse(pattern, converters, fields, false, false, false);
        patternFields = fields.toArray(FormattingInfo.EMPTY_ARRAY);
        final ArrayPatternConverter[] converterArray = new ArrayPatternConverter[converters.size()];
        patternConverters = converters.toArray(converterArray);
        this.fileExtension = FileExtension.lookupForFile(pattern);

        for (final ArrayPatternConverter converter : patternConverters) {
            if (converter instanceof DatePatternConverter) {
                final DatePatternConverter dateConverter = (DatePatternConverter) converter;
                frequency = calculateFrequency(dateConverter.getPattern());
            }
        }
    }

    /**
     * Copy constructor with another pattern as source.
     *
     * @param pattern  The file pattern.
     * @param copy Source pattern processor
     */
    public PatternProcessor(final String pattern, final PatternProcessor copy) {
        this(pattern);
        this.prevFileTime = copy.prevFileTime;
        this.currentFileTime = copy.currentFileTime;
    }

    public FormattingInfo[] getPatternFields() {
        return patternFields;
    }

    public ArrayPatternConverter[] getPatternConverters() {
        return patternConverters;
    }

    public long getCurrentFileTime() {
        return currentFileTime;
    }

    public void setCurrentFileTime(final long currentFileTime) {
        this.currentFileTime = currentFileTime;
    }

    public long getPrevFileTime() {
        return prevFileTime;
    }

    public void setPrevFileTime(final long prevFileTime) {
        LOGGER.debug("Setting prev file time to {}", new Date(prevFileTime));
        this.prevFileTime = prevFileTime;
    }

    public FileExtension getFileExtension() {
        return fileExtension;
    }

    private String format(final long time) {
        return new SimpleDateFormat("yyyy/MM/dd-HH:mm:ss.SSS").format(new Date(time));
    }

    /**
     * Format file name.
     * @param buf string buffer to which formatted file name is appended, may not be null.
     * @param obj object to be evaluated in formatting, may not be null.
     */
    public final void formatFileName(final StringBuilder buf, final boolean useCurrentTime, final Object obj) {
        long time = useCurrentTime ? currentFileTime : prevFileTime;
        if (time == 0) {
            time = System.currentTimeMillis();
        }
        formatFileName(buf, new Date(time), obj);
    }

    /**
     * Formats file name.
     * @param subst The StrSubstitutor.
     * @param buf string buffer to which formatted file name is appended, may not be null.
     * @param obj object to be evaluated in formatting, may not be null.
     */
    public final void formatFileName(final StrSubstitutor subst, final StringBuilder buf, final Object obj) {
        formatFileName(subst, buf, false, obj);
    }

    /**
     * Formats file name.
     * @param subst The StrSubstitutor.
     * @param buf string buffer to which formatted file name is appended, may not be null.
     * @param obj object to be evaluated in formatting, may not be null.
     */
    public final void formatFileName(final StrSubstitutor subst, final StringBuilder buf, final boolean useCurrentTime,
                                     final Object obj) {
        // LOG4J2-628: we deliberately use System time, not the log4j.Clock time
        // for creating the file name of rolled-over files.
        LOGGER.debug("Formatting file name. useCurrentTime={}. currentFileTime={}, prevFileTime={}",
            useCurrentTime, currentFileTime, prevFileTime);
        final long time = useCurrentTime ? currentFileTime != 0 ? currentFileTime : System.currentTimeMillis() :
                prevFileTime != 0 ? prevFileTime : System.currentTimeMillis();
        formatFileName(buf, new Date(time), obj);
        final LogEvent event = new Log4jLogEvent.Builder().setTimeMillis(time).build();
        final String fileName = subst.replace(event, buf);
        buf.setLength(0);
        buf.append(fileName);
    }

    /**
     * Formats file name.
     * @param buf string buffer to which formatted file name is appended, may not be null.
     * @param objects objects to be evaluated in formatting, may not be null.
     */
    protected final void formatFileName(final StringBuilder buf, final Object... objects) {
        for (int i = 0; i < patternConverters.length; i++) {
            final int fieldStart = buf.length();
            patternConverters[i].format(buf, objects);

            if (patternFields[i] != null) {
                patternFields[i].format(fieldStart, buf);
            }
        }
    }

    private RolloverFrequency calculateFrequency(final String pattern) {
        if (patternContains(pattern, MILLIS_CHAR)) {
            return RolloverFrequency.EVERY_MILLISECOND;
        }
        if (patternContains(pattern, SECOND_CHAR)) {
            return RolloverFrequency.EVERY_SECOND;
        }
        if (patternContains(pattern, MINUTE_CHAR)) {
            return RolloverFrequency.EVERY_MINUTE;
        }
        if (patternContains(pattern, HOUR_CHARS)) {
            return RolloverFrequency.HOURLY;
        }
        if (patternContains(pattern, DAY_CHARS)) {
            return RolloverFrequency.DAILY;
        }
        if (patternContains(pattern, WEEK_CHARS)) {
            return RolloverFrequency.WEEKLY;
        }
        if (patternContains(pattern, MONTH_CHAR)) {
            return RolloverFrequency.MONTHLY;
        }
        if (patternContains(pattern, YEAR_CHAR)) {
            return RolloverFrequency.ANNUALLY;
        }
        return null;
    }

    private PatternParser createPatternParser() {

        return new PatternParser(null, KEY, null);
    }

    private boolean patternContains(final String pattern, final char... chars) {
        for (final char character : chars) {
            if (patternContains(pattern, character)) {
                return true;
            }
        }
        return false;
    }

    private boolean patternContains(final String pattern, final char character) {
        return pattern.indexOf(character) >= 0;
    }

    public RolloverFrequency getFrequency() {
        return frequency;
    }

}
