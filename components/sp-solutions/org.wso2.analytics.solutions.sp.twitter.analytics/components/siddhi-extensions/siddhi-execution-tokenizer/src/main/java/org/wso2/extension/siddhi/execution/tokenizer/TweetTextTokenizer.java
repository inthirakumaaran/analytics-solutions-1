/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
package org.wso2.extension.siddhi.execution.tokenizer;

import io.siddhi.annotation.Example;
import io.siddhi.annotation.Extension;
import io.siddhi.annotation.Parameter;
import io.siddhi.annotation.util.DataType;
import io.siddhi.core.config.SiddhiQueryContext;
import io.siddhi.core.event.ComplexEventChunk;
import io.siddhi.core.event.stream.MetaStreamEvent;
import io.siddhi.core.event.stream.StreamEvent;
import io.siddhi.core.event.stream.StreamEventCloner;
import io.siddhi.core.event.stream.holder.StreamEventClonerHolder;
import io.siddhi.core.event.stream.populater.ComplexEventPopulater;
import io.siddhi.core.exception.SiddhiAppCreationException;
import io.siddhi.core.executor.ExpressionExecutor;
import io.siddhi.core.query.processor.ProcessingMode;
import io.siddhi.core.query.processor.Processor;
import io.siddhi.core.query.processor.stream.StreamProcessor;
import io.siddhi.core.util.config.ConfigReader;
import io.siddhi.core.util.snapshot.state.State;
import io.siddhi.core.util.snapshot.state.StateFactory;
import io.siddhi.query.api.definition.AbstractDefinition;
import io.siddhi.query.api.definition.Attribute;
import org.apache.log4j.Logger;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * splits a string into words
 */

@Extension(
        name = "tokenize",
        namespace = "text",
        description = "This splits a string into words",
        parameters = {
                @Parameter(name = "text",
                        description = "The input text which should be split.",
                        type = {DataType.STRING}),
        },
        examples = @Example(
                syntax = "define stream inputStream (text string);\n" +
                        "@info(name = 'query1')\n" +
                        "from inputStream#text:tokenize(text)\n" +
                        "select text\n" +
                        "insert into outputStream;",
                description = "This query performs tokenization for the given string.")
)

public class TweetTextTokenizer extends StreamProcessor<State> {
    private static final Logger log = Logger.getLogger(TweetTextTokenizer.class);

    private List<String> wordList = new ArrayList<>();
    private List<Attribute> attributeList = new ArrayList<>();

    @Override
    protected void process(ComplexEventChunk<StreamEvent> streamEventChunk, Processor nextProcessor,
                           StreamEventCloner streamEventCloner, ComplexEventPopulater complexEventPopulater,
                           State state) {
        //Urls
        String urlPattern = "(https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]";
        // Punctuation chars
        String punctChars = "[\\s+'“”‘’\\\".?!,:;&]";
        String brackets = "[" + "<>«»{}\\(\\)\\[\\]" + "]";
        String decorations = "[♫♪]+";
        // Numeric
        String timeLike = "\\d+:\\d+";
        String numNum = "\\d+\\.\\d+";
        Pattern pattern = Pattern.compile(punctChars + "|" + brackets + "|" + timeLike + "|" + numNum
                + "|" + decorations);
        String regexPattern = urlPattern + "|" + "@(.*)" + "|" + "#(.*)" + "|" + "[0-9]+" + "|" + "‼" + "|" + "…";
        while (streamEventChunk.hasNext()) {
            StreamEvent streamEvent = streamEventChunk.next();
            String event = (String) attributeExpressionExecutors[0].execute(streamEvent);
            event = removeEmojis(event);
            event = event.replaceAll(regexPattern, "");
            String[] words = pattern.split(event);
            for (String word : words) {
                if (!word.equals("") && isMeaningful(word)) {
                    Object[] data = {word};
                    complexEventPopulater.populateComplexEvent(streamEvent, data);
                    nextProcessor.process(streamEventChunk);
                }
            }
        }
    }

    /**
     * The initialization method for {@link StreamProcessor}, which will be called before other methods and validate
     * the all configuration and getting the initial values.
     *
     * @param attributeExpressionExecutors are the executors of each attributes in the Function
     * @param configReader                 this hold the {@link StreamProcessor} extensions configuration reader.
     * @param siddhiQueryContext           The context of the Siddhi query
     */
    @Override
    protected StateFactory<State> init(MetaStreamEvent metaStreamEvent, AbstractDefinition inputDefinition,
                                ExpressionExecutor[] attributeExpressionExecutors, ConfigReader configReader,
                                StreamEventClonerHolder streamEventClonerHolder, boolean outputExpectsExpiredEvents,
                                boolean findToBeExecuted, SiddhiQueryContext siddhiQueryContext) {
        String line;
        if (attributeExpressionExecutors.length == 1) {
            if (attributeExpressionExecutors[0].getReturnType() != Attribute.Type.STRING) {
                throw new SiddhiAppCreationException("Text should be of type string. But found "
                        + attributeExpressionExecutors[0].getReturnType());
            }
        } else {
            throw new IllegalArgumentException(
                    "Invalid no of arguments passed to text:tokenize() function, "
                            + "required 1, but found " + attributeExpressionExecutors.length);
        }
        InputStream inputStream = TweetTextTokenizer.class.getResourceAsStream("/words.csv");
        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream,
                StandardCharsets.UTF_8))) {
            while ((line = bufferedReader.readLine()) != null) {
                wordList.add(line);
            }
        } catch (FileNotFoundException e) {
            log.error("File is not found : " + e.getMessage());
        } catch (IOException e) {
            log.error("Error occurred while reading file : " + e.getMessage());
        }
        List<Attribute> attributes = new ArrayList<>();
        attributes.add(new Attribute("token", Attribute.Type.STRING));
        this.attributeList = attributes;
        return null;
    }

    @Override
    public void start() {
        //Do Nothing
    }

    @Override
    public void stop() {
        //Do Nothing
    }

    /**
     * Checks whether the given word is meaningful or not.
     */
    private boolean isMeaningful(String word) {
        for (String words : wordList) {
           if (words.equalsIgnoreCase(word)) {
               return false;
           }
        }
        return true;
    }

    /**
     * It removes all emojis from the given text
     */
    private String removeEmojis(String text) {
        Pattern unicodeOutliers =
                Pattern.compile(
                        "[\ud83c\udc00-\ud83c\udfff]|[\ud83d\udc00-\ud83d\udfff]|[\u2600-\u27ff]",
                        Pattern.UNICODE_CASE | Pattern.CANON_EQ | Pattern.CASE_INSENSITIVE
                );
        Matcher unicodeOutlierMatcher = unicodeOutliers.matcher(text);
        text = unicodeOutlierMatcher.replaceAll("");
        return  text;
    }

    @Override
    public List<Attribute> getReturnAttributes() {
        return this.attributeList;
    }

    @Override
    public ProcessingMode getProcessingMode() {
        return ProcessingMode.SLIDE;
    }
}
