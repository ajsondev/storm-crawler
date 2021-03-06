/**
 * Licensed to DigitalPebble Ltd under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * DigitalPebble licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.digitalpebble.stormcrawler.parse;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.slf4j.LoggerFactory;
import org.w3c.dom.DocumentFragment;

import com.digitalpebble.stormcrawler.util.ConfUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * Wrapper for the ParseFilters defined in a JSON configuration
 */
public class ParseFilters extends ParseFilter {

    public static final ParseFilters emptyParseFilter = new ParseFilters();

    private static final org.slf4j.Logger LOG = LoggerFactory
            .getLogger(ParseFilters.class);

    private ParseFilter[] filters;

    private ParseFilters() {
        filters = new ParseFilter[0];
    }

    /**
     * Loads and configure the ParseFilters based on the storm config if there
     * is one otherwise returns an emptyParseFilter.
     **/
    @SuppressWarnings("rawtypes")
    public static ParseFilters fromConf(Map stormConf) {
        String parseconfigfile = ConfUtils.getString(stormConf,
                "parsefilters.config.file");
        if (StringUtils.isNotBlank(parseconfigfile)) {
            try {
                return new ParseFilters(stormConf, parseconfigfile);
            } catch (IOException e) {
                String message = "Exception caught while loading the ParseFilters from "
                        + parseconfigfile;
                LOG.error(message);
                throw new RuntimeException(message, e);
            }
        }

        return ParseFilters.emptyParseFilter;
    }

    /**
     * loads the filters from a JSON configuration file
     * 
     * @throws IOException
     */
    @SuppressWarnings("rawtypes")
    public ParseFilters(Map stormConf, String configFile) throws IOException {
        // load the JSON configFile
        // build a JSON object out of it
        JsonNode confNode = null;
        InputStream confStream = null;
        try {
            confStream = getClass().getClassLoader().getResourceAsStream(
                    configFile);

            ObjectMapper mapper = new ObjectMapper();
            confNode = mapper.readValue(confStream, JsonNode.class);
        } catch (Exception e) {
            throw new IOException("Unable to build JSON object from file", e);
        } finally {
            if (confStream != null) {
                confStream.close();
            }
        }

        configure(stormConf, confNode);
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void configure(Map stormConf, JsonNode filtersConf) {
        // initialises the filters
        List<ParseFilter> filterLists = new ArrayList<>();

        // get the filters part
        String name = getClass().getCanonicalName();
        filtersConf = filtersConf.get(name);

        if (filtersConf == null) {
            LOG.info("No field {} in JSON config. Skipping", name);
            filters = new ParseFilter[0];
            return;
        }

        // conf node contains a list of objects
        Iterator<JsonNode> filterIter = filtersConf.elements();
        while (filterIter.hasNext()) {
            JsonNode afilterConf = filterIter.next();
            String filterName = "<unnamed>";
            JsonNode nameNode = afilterConf.get("name");
            if (nameNode != null) {
                filterName = nameNode.textValue();
            }
            JsonNode classNode = afilterConf.get("class");
            if (classNode == null) {
                LOG.error("Filter {} doesn't specified a 'class' attribute",
                        filterName);
                continue;
            }
            String className = classNode.textValue().trim();
            filterName += '[' + className + ']';
            // check that it is available and implements the interface
            // ParseFilter
            try {
                Class<?> filterClass = Class.forName(className);
                boolean subClassOK = ParseFilter.class
                        .isAssignableFrom(filterClass);
                if (!subClassOK) {
                    LOG.error("Filter {} does not extend ParseFilter",
                            filterName);
                    continue;
                }
                ParseFilter filterInstance = (ParseFilter) filterClass
                        .newInstance();

                JsonNode paramNode = afilterConf.get("params");
                if (paramNode != null) {
                    filterInstance.configure(stormConf, paramNode);
                } else {
                    // Pass in a nullNode if missing
                    filterInstance.configure(stormConf, NullNode.getInstance());
                }

                filterLists.add(filterInstance);
                LOG.info("Setup {}", filterName);
            } catch (Exception e) {
                LOG.error("Can't setup {}: {}", filterName, e);
                throw new RuntimeException("Can't setup " + filterName, e);
            }
        }

        filters = filterLists.toArray(new ParseFilter[filterLists.size()]);
    }

    @Override
    public boolean needsDOM() {
        for (ParseFilter filter : filters) {
            boolean needsDOM = filter.needsDOM();
            if (needsDOM) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void filter(String URL, byte[] content, DocumentFragment doc,
            ParseResult parse) {

        for (ParseFilter filter : filters) {
            long start = System.currentTimeMillis();
            if (doc == null && filter.needsDOM()) {
                LOG.info(
                        "ParseFilter {} needs DOM but has none to work on - skip : {}",
                        filter.getClass().getName(), URL);
                continue;
            }
            filter.filter(URL, content, doc, parse);
            long end = System.currentTimeMillis();
            LOG.debug("ParseFilter {} took {} msec", filter.getClass()
                    .getName(), end - start);
        }
    }

}
