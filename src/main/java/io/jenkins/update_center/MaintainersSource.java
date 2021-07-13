/*
 * The MIT License
 *
 * Copyright (c) 2021, Daniel Beck
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.jenkins.update_center;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.fastjson.annotation.JSONField;
import io.jenkins.update_center.util.Environment;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class MaintainersSource {
    private static final Logger LOGGER = Logger.getLogger(MaintainersSource.class.getName());

    private static final String PLUGIN_MAINTAINERS_DATA_URL = Environment.getString("PLUGIN_MAINTAINERS_DATA_URL", "https://reports.jenkins.io/maintainers.index.json");
    private static final String MAINTAINERS_INFO_URL = Environment.getString("MAINTAINERS_INFO_URL", "https://reports.jenkins.io/maintainers-info-report.json");

    private Map<String, List<String>> pluginToMaintainers;
    private Map<String, Maintainer> maintainerInfo;

    /**
     * Utility class for parsing JSON from {@link #MAINTAINERS_INFO_URL}.
     */
    private static class JsonMaintainer {
        @JSONField
        public String displayName;

        @JSONField
        public String name;

        private Maintainer toMaintainer() {
            return new Maintainer(name, displayName);
        }
    }

    public static class Maintainer {
        private final String name;
        private final String developerId;

        public Maintainer(String id, String name) {
            this.developerId = id;
            this.name = name;
        }

        public String getDeveloperId() {
            return developerId;
        }

        public String getName() {
            return name;
        }
    }

    private static MaintainersSource instance;

    public static synchronized MaintainersSource getInstance() {
        if (instance == null) {
            MaintainersSource ms = new MaintainersSource();
            ms.init();
            instance = ms;
        }
        return instance;
    }

    private void init() {
        // Obtain maintainer info
        try {
            final String jsonData = IOUtils.toString(new URL(MAINTAINERS_INFO_URL), StandardCharsets.UTF_8);
            final List<JsonMaintainer> rawMaintainersInfo = JSON.parseObject(jsonData, new TypeReferenceForListOfJsonMaintainer().getType());
            maintainerInfo = new HashMap<>();
            rawMaintainersInfo.forEach(m -> {
                if (maintainerInfo.containsKey(m.name)) {
                    LOGGER.warning("Duplicate entry for " + m.name + " in " + MAINTAINERS_INFO_URL);
                }
                maintainerInfo.put(m.name, m.toMaintainer());
            });
        } catch (RuntimeException | IOException ex) {
            LOGGER.log(Level.WARNING, "Failed to process " + MAINTAINERS_INFO_URL, ex);
            maintainerInfo = new HashMap<>();
        }

        // Obtain plugin/maintainers mapping
        try {
            final String jsonData = IOUtils.toString(new URL(PLUGIN_MAINTAINERS_DATA_URL), StandardCharsets.UTF_8);
            pluginToMaintainers = JSON.parseObject(jsonData, new TypeReferenceForHashMapFromStringToListOfString().getType());
        } catch (RuntimeException | IOException ex) {
            pluginToMaintainers = new HashMap<>();
            LOGGER.log(Level.WARNING, "Failed to process" + PLUGIN_MAINTAINERS_DATA_URL, ex);
        }
    }

    private List<String> getMaintainerIDs(ArtifactCoordinates plugin) {
        final String ga = plugin.groupId + ":" + plugin.artifactId;
        if (pluginToMaintainers.containsKey(ga)) {
            return pluginToMaintainers.get(ga);
        }
        final List<String> candidateGAs = pluginToMaintainers.keySet().stream().filter(s -> s.endsWith(":" + plugin.artifactId)).collect(Collectors.toList());
        switch (candidateGAs.size()) {
            case 1:
                final String key = candidateGAs.get(0);
                LOGGER.log(Level.INFO, "Apparent mismatch of group IDs between permissions assignment: " + key + " and latest available release of plugin: " + plugin);
                return pluginToMaintainers.get(key);
            case 0:
                // No maintainer information found
                LOGGER.log(Level.INFO, "No maintainer information found for plugin: " + plugin);
                return new ArrayList<>();
            default: // 2+ candidate artifacts but none match exactly
                LOGGER.log(Level.WARNING, "Multiple artifact IDs match, but none exactly. Will not provide maintainer information for plugin: " + plugin);
                return new ArrayList<>();
        }
    }

    /**
     * Return the list of maintainers for the specified plugin.
     *
     * @param plugin the plugin.
     * @return list of maintainers
     */
    public List<Maintainer> getMaintainers(ArtifactCoordinates plugin) {
        return getMaintainerIDs(plugin).stream().map((String key) -> maintainerInfo.getOrDefault(key, new Maintainer(key, null))).collect(Collectors.toList());
    }

    private static class TypeReferenceForListOfJsonMaintainer extends TypeReference<List<JsonMaintainer>> {
    }
    private static class TypeReferenceForHashMapFromStringToListOfString extends TypeReference<HashMap<String, List<String>>> {
    }
}
