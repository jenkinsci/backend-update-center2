/*
 * The MIT License
 *
 * Copyright (c) 2004-2020, Sun Microsystems, Inc. and other contributors
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

import com.alibaba.fastjson.annotation.JSONField;
import com.google.common.annotations.VisibleForTesting;
import hudson.util.VersionNumber;
import io.jenkins.update_center.util.JavaSpecificationVersion;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentFactory;
import org.dom4j.Element;
import org.dom4j.Node;
import org.dom4j.io.SAXReader;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.HtmlSanitizer;
import org.owasp.html.HtmlStreamEventProcessor;
import org.owasp.html.HtmlStreamEventReceiver;
import org.owasp.html.HtmlStreamEventReceiverWrapper;
import org.owasp.html.HtmlStreamRenderer;
import org.owasp.html.Sanitizers;
import org.xml.sax.SAXException;

import javax.annotation.CheckForNull;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A particular version of a plugin and its metadata.
 *
 * For version independent metadata, see {@link Plugin}.
 */
public class HPI extends MavenArtifact {

    private final Plugin plugin;

    public HPI(BaseMavenRepository repository, ArtifactCoordinates artifact, Plugin plugin) {
        super(repository, artifact);
        this.plugin = plugin;
    }

    public Plugin getPlugin() {
        return plugin;
    }

    /**
     * Download a plugin via more intuitive URL. This also helps us track download counts.
     */
    public URL getDownloadUrl() throws MalformedURLException {
        return new URL(StringUtils.removeEnd(DOWNLOADS_ROOT_URL, "/") + "/plugins/" + artifact.artifactId + "/" + version + "/" + artifact.artifactId + ".hpi");
    }

    public String getRequiredJenkinsVersion() throws IOException {
        String v = getManifestAttributes().getValue("Jenkins-Version");
        if (v!=null)        return v;

        v = getManifestAttributes().getValue("Hudson-Version");
        if (fixNull(v) != null) {
            try {
                VersionNumber n = new VersionNumber(v);
                if (n.compareTo(JenkinsWar.HUDSON_CUT_OFF)<=0)
                    return v;   // Hudson <= 1.395 is treated as Jenkins
                // TODO: Jenkins-Version started appearing from Jenkins 1.401 POM.
                // so maybe Hudson > 1.400 shouldn't be considered as a Jenkins plugin?
            } catch (IllegalArgumentException e) {
            }
        }

        // Parent versions 1.393 to 1.398 failed to record requiredCore.
        // If value is missing, let's default to 1.398 for now.
        return "1.398";
    }

    /**
     * Earlier versions of the maven-hpi-plugin put "null" string literal, so we need to treat it as real null.
     */
    private static String fixNull(String v) {
        if("null".equals(v))    return null;
        return v;
    }

    public String getCompatibleSinceVersion() throws IOException {
        return getManifestAttributes().getValue("Compatible-Since-Version");
    }

    /**
     * Gets Minimum Java Version required by the plugin.
     * This uses the value of the {@code Minimum-Java-Version} manifest entry
     * @return Minimum Java Version or {@code null} if it is unknown
     * @throws IOException Manifest read error
     */
    @CheckForNull
    public JavaSpecificationVersion getMinimumJavaVersion() throws IOException {
        String manifestEntry = getManifestAttributes().getValue("Minimum-Java-Version");
        if (StringUtils.isNotBlank(manifestEntry)) {
            return new JavaSpecificationVersion(manifestEntry);
        }

        return null;
    }

    public List<Dependency> getDependencies() throws IOException {
        String deps = getManifestAttributes().getValue("Plugin-Dependencies");
        if(deps==null)  return Collections.emptyList();

        List<Dependency> r = new ArrayList<>();
        for(String token : deps.split(","))
            r.add(new Dependency(token));
        return r;
    }

    private String plainText2html(String plainText) {
        if (plainText == null || plainText.length() == 0) {
            return "";
        }
        return plainText.replace("&","&amp;").replace("<","&lt;");
    }

    private static final Properties URL_OVERRIDES = new Properties();
    private static final Properties LABEL_DEFINITIONS = new Properties();
    private static final Properties ALLOWED_GITHUB_LABELS = new Properties();

    static {
        try (
            InputStream overridesStream = Files.newInputStream(new File(Main.resourcesDir, "wiki-overrides.properties").toPath());
            InputStream labelStream = Files.newInputStream(new File(Main.resourcesDir, "label-definitions.properties").toPath());
            InputStream allowedTopicsStream = Files.newInputStream(new File(Main.resourcesDir, "allowed-github-topics.properties").toPath())) {
            URL_OVERRIDES.load(overridesStream);
            LABEL_DEFINITIONS.load(labelStream);
            ALLOWED_GITHUB_LABELS.load(allowedTopicsStream);

        } catch (IOException e) {
            throw new Error(e);
        }
    }

    private String description;

    public String getDescription() throws IOException {
        if (description == null) {
            String description = plainText2html(readSingleValueFromXmlFile(resolvePOM(), "/project/description"));

            ArtifactCoordinates coordinates = new ArtifactCoordinates(artifact.groupId, artifact.artifactId, artifact.version, "jar");
            try (InputStream is = repository.getZipFileEntry(new MavenArtifact(repository, coordinates), "index.jelly")) {
                StringBuilder b = new StringBuilder();
                HtmlStreamRenderer renderer = HtmlStreamRenderer.create(b, Throwable::printStackTrace, html -> LOGGER.log(Level.INFO, "Bad HTML: '" + html + "' in " + artifact.getGav()));
                HtmlSanitizer.sanitize(IOUtils.toString(is, StandardCharsets.UTF_8), HTML_POLICY.apply(renderer), PRE_PROCESSOR);
                description = b.toString().trim().replaceAll("\\s+", " ");
            } catch (IOException e) {
                LOGGER.log(Level.FINE, () -> "Failed to read description from index.jelly: " + e.getMessage());
            }
            if (isAlphaOrBeta()) {
                description = "<b>(This version is experimental and may change in backward-incompatible ways)</b><br><br>" + description;
            }
            this.description = description;
        }
        return description;
    }

    public static class Dependency {
        @JSONField
        public final String name;
        @JSONField
        public final String version;
        @JSONField
        public final boolean optional;

        public Dependency(String token) {
            this.optional = token.endsWith(OPTIONAL_RESOLUTION);
            if(optional)
                token = token.substring(0, token.length()-OPTIONAL_RESOLUTION.length());

            String[] pieces = token.split(":");
            name = pieces[0];
            version = pieces[1];
        }

        private static final String OPTIONAL_RESOLUTION = ";resolution:=optional";
    }

    private String name;

    /**
     * @return The plugin name defined in the POM &lt;name&gt; modified by simplification rules (no 'Jenkins', no 'Plugin'); then artifact ID.
     * @throws IOException if an exception occurs while accessing metadata
     */
    public String getName() throws IOException {
        if (name == null) {
            String title = readSingleValueFromXmlFile(resolvePOM(), "/project/name");
            if (title == null || "".equals(title)) {
                title = artifact.artifactId;
            } else {
                title = simplifyPluginName(title);
            }
            name = title;
        }
        return name;
    }

    @VisibleForTesting
    static String simplifyPluginName(String name) {
        name = org.apache.commons.lang3.StringUtils.removeStart(name, "Jenkins ");
        name = org.apache.commons.lang3.StringUtils.removeStart(name, "Hudson ");
        name = org.apache.commons.lang3.StringUtils.removeEndIgnoreCase(name, " for Jenkins");
        name = org.apache.commons.lang3.StringUtils.removeEndIgnoreCase(name, " Jenkins Plugin");
        name = org.apache.commons.lang3.StringUtils.removeEndIgnoreCase(name, " Plugin");
        name = org.apache.commons.lang3.StringUtils.removeEndIgnoreCase(name, " Plug-In");
        name = name.replaceAll("[- .!]+$", ""); // remove trailing punctuation e.g. for 'Acme Foo - Jenkins Plugin'
        return name;
    }

    private String readSingleValueFromXmlFile(File file, String xpath) {
        try {
            XmlCache.CachedValue cached = XmlCache.readCache(file, xpath);
            if (cached == null) {
                Document doc = xmlReader.read(file);
                Node node = selectSingleNode(doc, xpath);
                String ret = node != null ? ((Element) node).getTextTrim() : null;
                XmlCache.writeCache(file, xpath, ret);
                return ret;
            } else {
                return cached.value;
            }
        } catch (DocumentException e) {
            return null;
        }
    }

    private static Node selectSingleNode(Document pom, String path) {
        Node result = pom.selectSingleNode(path);
        if (result == null)
            result = pom.selectSingleNode(path.replaceAll("/", "/m:"));
        return result;
    }

    private Document getPom() throws IOException {
        if (pom == null) {
            pom = readPOM();
        }
        return pom;
    }

    /**
     * POM parsed as a DOM.
     */
    private Document pom;

    private static SAXReader createXmlReader() {
        DocumentFactory factory = new DocumentFactory();
        factory.setXPathNamespaceURIs(
                Collections.singletonMap("m", "http://maven.apache.org/POM/4.0.0"));
        final SAXReader reader = new SAXReader(factory);
        try {
            reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (SAXException ex) {
            LOGGER.log(Level.WARNING, "Failed to set safety features on SAXReader", ex);
        }
        return reader;
    }

    private Document readPOM() throws IOException {
        try {
            return xmlReader.read(resolvePOM());
        } catch (DocumentException e) {
            LOGGER.log(Level.INFO, "Failed to parse POM for " + artifact.getGav(), e);
            return null;
        }
    }

    private String pluginUrl;

    /**
     * @return The URL as specified in the POM, or the overrides file.
     * @throws IOException if an error occurs while accessing plugin metadata
     */
    public String getPluginUrl() throws IOException {
        if (pluginUrl == null) {
            // Check whether the plugin documentation URL should be overridden
            String url = URL_OVERRIDES.getProperty(artifact.artifactId);

            // Otherwise read *.hpi!/META-INF/MANIFEST.MF#Url, if defined
            if (url == null) {
                url = getManifestAttributes().getValue("Url");
            }

            // Otherwise read the plugin URL from the POM, if any
            if (url == null) {
                url = readSingleValueFromXmlFile(resolvePOM(), "/project/url");
            }
            // last fallback: GitHub URL; also prevent plugins.j.io referencing itself
            if (url == null || url.startsWith("https://plugins.jenkins.io")) {
                url = requireTopLevelUrl(getScmUrl());
            }
            String originalUrl = url;

            if (url != null) {
                url = url.replace("wiki.hudson-ci.org/display/HUDSON/", "wiki.jenkins-ci.org/display/JENKINS/");
                url = url.replace("http://wiki.jenkins-ci.org", "https://wiki.jenkins.io");
            }

            if (url != null && !url.equals(originalUrl)) {
                LOGGER.fine("Rewrote URL for plugin " + artifact.getGav() + " from " + originalUrl + " to " + url);
            }
            pluginUrl = url;
        }
        return pluginUrl;
    }

    @VisibleForTesting
    static String requireTopLevelUrl(String scmUrl) {
        if (scmUrl == null) {
            return null;
        }
        String[] parts = scmUrl.split("/");
        if (parts.length > 5){
            return null;
        }
        return scmUrl;
    }

    private String filterKnownObsoleteUrls(String scm) {
        if (scm == null) {
            // couldn't be determined from /project/scm/url in pom or parent pom
            return null;
        }
        if (scm.contains("fisheye.jenkins-ci.org")) {
            // well known historical URL that won't help
            return null;
        }
        if (scm.contains("svn.jenkins-ci.org")) {
            // well known historical URL that won't help
            return null;
        }
        if (scm.contains("svn.java.net")) {
            // well known historical URL that won't help
            return null;
        }
        if (scm.contains("svn.dev.java.net")) {
            // well known historical URL that won't help
            return null;
        }
        if (scm.contains("hudson.dev.java.net")) {
            // well known historical URL that won't help
            return null;
        }
        if (scm.contains("jenkinsci/plugin-pom")) {
            // this is a plugin based on the parent POM without a <scm> blocck
            return null;
        }
        return scm;
    }

    private String _getScmUrl() {
        try {
            String scm = readSingleValueFromXmlFile(resolvePOM(), "/project/scm/url");
            // Try parent pom
            if (scm == null) {
                LOGGER.log(Level.FINER, "No SCM URL found in POM for " + this.artifact.getGav());
                Element parent = (Element) selectSingleNode(getPom(), "/project/parent");
                if (parent != null) {
                    try {
                        File parentPomFile = repository.resolve(
                                new ArtifactCoordinates(parent.element("groupId").getTextTrim(),
                                        parent.element("artifactId").getTextTrim(),
                                        parent.element("version").getTextTrim(), "pom"));
                        scm = readSingleValueFromXmlFile(parentPomFile, "/project/scm/url");
                        if (scm == null) {
                            LOGGER.log(Level.FINER, "No SCM URL found in parent POM for " + this.artifact.getGav());
                            // grandparent is pointless, no additional hits
                        }
                    } catch (Exception ex) {
                        LOGGER.log(Level.WARNING, "Failed to read parent POM for " + this.artifact.getGav(), ex);
                    }
                }
            }
            if (scm == null) {
                return null;
            }
            if (filterKnownObsoleteUrls(scm) == null) {
                LOGGER.log(Level.FINE, "Filtered obsolete URL " + scm + " in SCM URL for " + this.artifact.getGav());
                return null;
            }
            return scm;
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Failed to read POM for " + this.artifact.getGav(), ex);
        }
        return null;
    }

    private String getScmUrlFromDeveloperConnection() {
        try {
            String scm = readSingleValueFromXmlFile(resolvePOM(), "/project/scm/developerConnection");
            // Try parent pom
            if (scm == null) {
                LOGGER.log(Level.FINE, "No SCM developerConnection found in POM for " + this.artifact.getGav());
                Element parent = (Element) selectSingleNode(getPom(), "/project/parent");
                if (parent != null) {
                    try {
                        File parentPomFile = repository.resolve(
                                new ArtifactCoordinates(parent.element("groupId").getTextTrim(),
                                        parent.element("artifactId").getTextTrim(),
                                        parent.element("version").getTextTrim(), "pom"));
                        scm = readSingleValueFromXmlFile(parentPomFile, "/project/scm/developerConnection");
                        if (scm == null) {
                            LOGGER.log(Level.FINE, "No SCM developerConnection found in parent POM for " + this.artifact.getGav());
                        }
                    } catch (Exception ex) {
                        LOGGER.log(Level.WARNING, "Failed to read parent POM for " + this.artifact.getGav(), ex);
                    }
                }
            }
            if (scm == null) {
                return null;
            }
            if (filterKnownObsoleteUrls(scm) == null) {
                LOGGER.log(Level.FINE, "Filtered obsolete URL " + scm + " in SCM developerConnection for " + this.artifact.getGav());
                return null;
            }
            return scm;
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Failed to read POM for " + this.artifact.getGav(), ex);
        }
        return null;
    }

    private String interpolateProjectName(String str) {
        if (str == null) {
            return null;
        }
        str = str.replace("${project.artifactId}", artifact.artifactId);
        str = str.replace("${artifactId}", artifact.artifactId);
        return str;
    }

    private String requireHttpsGitHubJenkinsciUrl(String url) {
        if (url == null) {
            return null;
        }
        if (url.contains("github.com:jenkinsci/") || url.contains("github.com/jenkinsci/")) {
            // We're only doing weird thing for GitHub URLs that map somewhat cleanly from developerConnection to browsable URL.
            // Also limit to jenkinsci because that's what people should be using anyway.
            String githubUrl = url.substring(url.indexOf("github.com"));
            githubUrl = githubUrl.replace(":", "/");
            if (githubUrl.endsWith(".git")) {
                // all should, but not all do
                githubUrl = githubUrl.substring(0, githubUrl.lastIndexOf(".git"));
            }
            if (githubUrl.endsWith("/")) {
                githubUrl = githubUrl.substring(0, githubUrl.lastIndexOf("/"));
            }
            return "https://" + githubUrl;
        }
        return null;
    }

    private String requireGitHubRepoExistence(String url) {
        GitHubSource gh = GitHubSource.getInstance();
        String shortenedUrl = org.apache.commons.lang3.StringUtils.removeEndIgnoreCase(url, "-plugin");
        return gh.isRepoExisting(url) ? url : (gh.isRepoExisting(shortenedUrl) ? shortenedUrl : null);
    }

    private String scmUrl;
    private boolean scmUrlCached; // separate status variable because 'null' has the 'undefined' meaning

    /**
     * Get the SCM URL of this component.
     * This tries to determine the URL from the POM and from GitHub (based on repo naming convention).
     *
     * @return a string representing a user-accessible SCM URL, like https://github.com/org/repo, or {code null} if the repo wasn't found or is considered invalid.
     * @throws IOException if an error occurs while accessing plugin metadata or GitHub
     */
    public String getScmUrl() throws IOException {
        if (!scmUrlCached) {
            scmUrlCached = true;
            if (resolvePOM().exists()) {
                String scm = _getScmUrl();
                if (scm == null) {
                    scm = getScmUrlFromDeveloperConnection();
                }
                if (scm == null) {
                    LOGGER.log(Level.FINE, "Failed to determine SCM URL from POM or parent POM of " + this.artifact.getGav());
                }
                scm = interpolateProjectName(scm);
                String originalScm = scm;
                scm = requireHttpsGitHubJenkinsciUrl(scm);
                if (originalScm != null && scm == null) {
                    LOGGER.log(Level.CONFIG, "Rejecting URL outside GitHub.com/jenkinsci for " + this.artifact.getGav() + ": " + originalScm);
                }

                if (scm == null) {
                    // Last resort: check whether a ${artifactId}-plugin repo in jenkinsci exists, if so, use that
                    scm = "https://github.com/jenkinsci/" + artifact.artifactId + "-plugin";
                    LOGGER.log(Level.FINE, "Falling back to default pattern repo for " + this.artifact.getGav() + ": " + scm);

                    String checkedScm = scm;
                    // Check whether the fallback repo actually exists, if not, don't publish the repo name
                    scm = requireGitHubRepoExistence(scm);
                    if (scm == null) {
                        LOGGER.log(Level.FINE, "Repository does not actually exist: " + checkedScm);
                    }
                }
                scmUrl = scm;
            }
        }
        return scmUrl;
    }

    private static class OrgAndRepo {
        private final String org;
        private final String repo;

        private OrgAndRepo(String org, String repo) {
            this.org = org;
            this.repo = repo;
        }
    }

    private OrgAndRepo getOrgAndRepo(String scmUrl) {
        if (scmUrl == null || !scmUrl.startsWith("https://github.com/")) {
            return null;
        }
        String[] parts = scmUrl.replaceFirst("https://github.com/", "").split("[/]");
        if (parts.length >= 2) {
            return new OrgAndRepo(parts[0], parts[1]);
        }
        return null;
    }

    private List<String> labels;

    public List<String> getLabels() throws IOException { // TODO this would be better in a different class, doesn't fit HPI type
        if (labels == null) {
            String scm = getScmUrl();

            List<String> gitHubLabels = new ArrayList<>();
            OrgAndRepo orgAndRepo = getOrgAndRepo(scm);
            if (orgAndRepo != null) {

                List<String> unsanitizedLabels = new ArrayList<>(Arrays.asList(
                        GitHubSource.getInstance().getRepositoryTopics(orgAndRepo.org, orgAndRepo.repo).toArray(new String[0])));

                for (String label : unsanitizedLabels) {
                    if (label.startsWith("jenkins-")) {
                        label = label.replaceFirst("jenkins-", "");
                    }

                    if (ALLOWED_GITHUB_LABELS.containsKey(label)) {
                        gitHubLabels.add(label);
                    }
                }

                if (!gitHubLabels.isEmpty()) {
                    LOGGER.log(Level.FINE, () -> artifact.artifactId + " got the following labels contributed from GitHub: " + org.apache.commons.lang3.StringUtils.join(gitHubLabels, ", "));
                }
            }

            Set<String> labels = new TreeSet<>(Arrays.asList(getLabelsFromFile()));
            labels.addAll(gitHubLabels);

            this.labels = new ArrayList<>(labels);
        }
        return this.labels;
    }

    private String defaultBranch;

    public String getDefaultBranch() throws IOException { // TODO this would be better in a different class, doesn't fit HPI type
        if (defaultBranch == null) {
            String scm = getScmUrl();

            OrgAndRepo orgAndRepo = getOrgAndRepo(scm);
            if (orgAndRepo != null) {
                defaultBranch = GitHubSource.getInstance().getDefaultBranch(orgAndRepo.org, orgAndRepo.repo);
            }
        }
        return defaultBranch;
    }

    // declared type is generic here because return value of com.google.common.base.Function::apply
    // (and hence PolicyFactory) is considered nullable, triggering SpotBugs warnings
    @VisibleForTesting
    public static final Function<HtmlStreamEventReceiver, HtmlSanitizer.Policy> HTML_POLICY = Sanitizers.FORMATTING.and(Sanitizers.LINKS).and(new HtmlPolicyBuilder().allowElements("a").requireRelsOnLinks("noopener", "noreferrer").allowAttributes("target").matching(false, "_blank").onElements("a").toFactory());

    @VisibleForTesting
    public static final HtmlStreamEventProcessor PRE_PROCESSOR = receiver -> new HtmlStreamEventReceiverWrapper(receiver) {
        @Override
        public void openTag(String elementName, List<String> attrs) {
            if ("a".equals(elementName)) {
                attrs.add("target");
                attrs.add("_blank");
            }
            super.openTag(elementName, attrs);
        }
    };

    private String[] getLabelsFromFile() {
        Object ret = LABEL_DEFINITIONS.get(artifact.artifactId);
        if (ret == null) {
            // handle missing entry in properties file
            return new String[0];
        }
        String labels = ret.toString();
        if (labels.trim().length() == 0) {
            // handle empty entry in properties file
            return new String[0];
        }
        return labels.split("\\s+");
    }

    private static final SAXReader xmlReader = createXmlReader();

    private static final Logger LOGGER = Logger.getLogger(HPI.class.getName());
}
