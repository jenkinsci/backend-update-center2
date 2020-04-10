/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc.
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
package org.jvnet.hudson.update_center;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.util.VersionNumber;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.jvnet.hudson.update_center.impl.pluginFilter.JavaVersionPluginFilter;
import org.jvnet.hudson.update_center.util.JavaSpecificationVersion;
import org.jvnet.hudson.update_center.wrappers.AlphaBetaOnlyRepository;
import org.jvnet.hudson.update_center.wrappers.StableMavenRepository;
import org.jvnet.hudson.update_center.wrappers.TruncatedMavenRepository;
import org.jvnet.hudson.update_center.wrappers.VersionCappedMavenRepository;
import org.kohsuke.args4j.ClassParser;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import javax.annotation.CheckForNull;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * @author Kohsuke Kawaguchi
 */
public class Main {
    public static final String DEFAULT_ID = "default";
    public static final String DEFAULT_CONNECTION_CHECK_URL = "http://www.google.com/"; // TODO go to https
    public File jsonp = new File("output.json");

    public File json = new File("actual.json");

    public File releaseHistory = new File("release-history.json");

    public File pluginVersions = new File("plugin-versions.json");

    public File urlmap = new File("plugin-to-documentation-url.json");

    private Map<String, String> pluginToDocumentationUrl = new HashMap<>();

    /**
     * This file defines all the convenient symlinks in the form of
     * ./latest/PLUGINNAME.hpi.
     */
    public File latest = new File("latest");

    /**
     * This option builds the directory image for the download server, which contains all the plugins
     * ever released to date in a directory structure.
     *
     * This is what we push into http://mirrors.jenkins-ci.org/ and from there it gets rsynced to
     * our mirror servers (some indirectly through OSUOSL.)
     *
     * TODO: it also currently produces war/ directory that we aren't actually using. Maybe remove?
     */
    @Option(name="-download",usage="Build mirrors.jenkins-ci.org layout")
    public File download = null;

    /**
     * This option generates a directory layout containing htaccess files redirecting to Artifactory
     * for all files contained therein. This can be used for the 'fallback' mirror server.
     */
    @Option(name="-download-fallback",usage="Build archives.jenkins-ci.org layout")
    public File downloadFallback = null;

    /**
     * This options builds update site. update-center.json(.html) that contains metadata,
     * latest symlinks, and download/ directories that are referenced from metadata and
     * redirects to the actual download server.
     */
    @Option(name="-www",usage="Build updates.jenkins-ci.org layout")
    public File www = null;

    /**
     * This options builds the http://updates.jenkins-ci.org/download files,
     * which consists of a series of index.html that lists available versions of plugins and cores.
     *
     * <p>
     * This is the URL space that gets referenced by update center metadata, and this is the
     * entry point of all the inbound download traffic. Actual *.hpi downloads are redirected
     * to mirrors.jenkins-ci.org via Apache .htaccess.
     */
    @Option(name="-www-download",usage="Build updates.jenkins-ci.org/download directory")
    public File wwwDownload = null;

    public File indexHtml = null;

    public File latestCoreTxt = null;

    @Option(name="-id",usage="Uniquely identifies this update center. We recommend you use a dot-separated name like \"com.sun.wts.jenkins\". This value is not exposed to users, but instead internally used by Jenkins.")
    public String id;

    @Option(name="-maxPlugins",usage="For testing purposes. Limit the number of plugins managed to the specified number.")
    public Integer maxPlugins;

    @Option(name="-connectionCheckUrl",
            usage="Specify an URL of the 'always up' server for performing connection check.")
    public String connectionCheckUrl;

    @Option(name="-pretty",usage="Pretty-print the result")
    public boolean prettyPrint;

    @Option(name="-cap",usage="Cap the version number and only report plugins that are compatible with ")
    public String capPlugin = null;

    @Option(name="-capCore",usage="Cap the version number and only core that's compatible with. Defaults to -cap")
    public String capCore = null;

    @Option(name="-stableCore", usage="Limit core releases to stable (LTS) releases (those with three component version numbers)")
    public boolean stableCore;

    @Option(name="-pluginCount.txt",usage="Report a number of plugins in a simple text file")
    public File pluginCountTxt = null;

    @Option(name="-experimental-only",usage="Include alpha/beta releases only")
    public boolean experimentalOnly;

    @Option(name="-no-experimental",usage="Exclude alpha/beta releases")
    public boolean noExperimental;

    @Option(name="-skip-release-history",usage="Skip generation of release history")
    public boolean skipReleaseHistory;

    @Option(name = "-javaVersion",usage = "Target Java version for the update center. " +
            "Plugins will be excluded if their minimum Java version does not match. " +
            "If not set, required Java version will be ignored")
    @CheckForNull
    public String javaVersion;

    @Option(name="-skip-plugin-versions",usage="Skip generation of plugin versions")
    public boolean skipPluginVersions;

    @Option(name="-arguments-file",usage="Specify invocation arguments in a file, with each line being a separate update site build. This argument cannot be re-set via arguments-file.")
    @SuppressFBWarnings
    public static File argumentsFile;

    @Option(name="-resources-dir", usage = "Specify the path to the resources directory containing warnings.json, artifact-ignores.properties, etc. This argument cannot be re-set via arguments-file.")
    @SuppressFBWarnings
    public static File resourcesDir = new File("resources"); // default for tests

    private Signer signer = new Signer();

    public static final String EOL = System.getProperty("line.separator");

    public static void main(String[] args) throws Exception {
        System.exit(new Main().run(args));
    }

    public int run(String[] args) throws Exception {
        CmdLineParser p = new CmdLineParser(this);
        new ClassParser().parse(signer, p);
        try {
            p.parseArgument(args);

            if (argumentsFile == null) {
                run();
            } else {
                List<String> invocations = IOUtils.readLines(Files.newBufferedReader(argumentsFile.toPath(), StandardCharsets.UTF_8));
                for (String line : invocations) {
                    if (!line.trim().startsWith("#") && !line.trim().isEmpty()) {

                        System.err.println("Running with args: " + line);
                        // TODO combine args array and this list
                        String[] invocationArgs = line.split(" +");

                        resetArguments();
                        this.signer = new Signer();
                        p = new CmdLineParser(this);
                        new ClassParser().parse(signer, p);
                        p.parseArgument(invocationArgs);
                        run();
                    }
                }
            }

            return 0;
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            p.printUsage(System.err);
            return 1;
        }
    }

    private void resetArguments() {
        for (Field field : this.getClass().getFields()) {
            if (field.getAnnotation(Option.class) != null && !Modifier.isStatic(field.getModifiers())) {
                if (Object.class.isAssignableFrom(field.getType())) {
                    try {
                        field.set(this, null);
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                } else if (boolean.class.isAssignableFrom(field.getType())) {
                    try {
                        field.set(this, false);
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private String getCapCore() {
        if (capCore!=null)  return capCore;
        return capPlugin;
    }

    private void prepareStandardDirectoryLayout() {
        json = new File(www,"update-center.actual.json");
        jsonp = new File(www,"update-center.json");
        urlmap = new File(www, "plugin-documentation-urls.json");

        latest = new File(www,"latest");
        indexHtml = new File(www,"index.html");
        pluginVersions = new File(www, "plugin-versions.json");
        releaseHistory = new File(www,"release-history.json");
        latestCoreTxt = new File(www,"latestCore.txt");
    }

    public void run() throws Exception {

        if (www!=null) {
            prepareStandardDirectoryLayout();
        }

        MavenRepository repo = createRepository();

        LatestLinkBuilder latest = createHtaccessWriter();

        JSONObject ucRoot = buildUpdateCenterJson(repo, latest);
        writeToFile(mapPluginToDocumentationUrl(), urlmap);
        writeToFile(updateCenterPostCallJson(ucRoot), jsonp);
        writeToFile(prettyPrintJson(ucRoot), json);
        writeToFile(updateCenterPostMessageHtml(ucRoot), new File(jsonp.getPath()+".html"));

        if (!skipPluginVersions) {
            writeToFile(prettyPrintJson(buildPluginVersionsJson(repo)), pluginVersions);
        }

        if (!skipReleaseHistory) {
            JSONObject rhRoot = buildFullReleaseHistory(repo);
            String rh = prettyPrintJson(rhRoot);
            writeToFile(rh, releaseHistory);
        }

        latest.close();
    }

    String mapPluginToDocumentationUrl() {
        if (pluginToDocumentationUrl.isEmpty()) {
            throw new IllegalStateException("Must run after buildUpdateCenterJson");
        }
        JSONObject root = new JSONObject();
        for (Map.Entry<String, String> entry : pluginToDocumentationUrl.entrySet()) {
            JSONObject value = new JSONObject();
            value.put("url", entry.getValue());
            root.put(entry.getKey(), value);
        }
        return root.toString();
    }

    String updateCenterPostCallJson(JSONObject ucRoot) {
        return "updateCenter.post(" + EOL + prettyPrintJson(ucRoot) + EOL + ");";
    }

    String updateCenterPostMessageHtml(JSONObject ucRoot) {
        // needs the DOCTYPE to make JSON.stringify work on IE8
        return "\uFEFF<!DOCTYPE html><html><head><meta http-equiv='Content-Type' content='text/html;charset=UTF-8' /></head><body><script>window.onload = function () { window.parent.postMessage(JSON.stringify(" + EOL + prettyPrintJson(ucRoot) + EOL + "),'*'); };</script></body></html>";
    }

    private LatestLinkBuilder createHtaccessWriter() throws IOException {
        latest.mkdirs();
        return new LatestLinkBuilder(latest);
    }

    private JSONObject buildPluginVersionsJson(MavenRepository repo) throws Exception {
        JSONObject root = new JSONObject();
        root.put("updateCenterVersion","1");    // we'll bump the version when we make incompatible changes
        root.put("plugins", buildPluginVersions(repo));

        if (signer.isConfigured())
            signer.sign(root);

        return root;
    }

    private JSONObject buildUpdateCenterJson(MavenRepository repo, LatestLinkBuilder latest) throws Exception {
        JSONObject root = new JSONObject();
        root.put("updateCenterVersion","1");    // we'll bump the version when we make incompatible changes
        JSONObject core = buildCore(repo, latest);
        if (core!=null)
            root.put("core", core);
        root.put("warnings", buildWarnings());
        root.put("plugins", buildPlugins(repo, latest));
        root.put("id",id == null ? DEFAULT_ID : id);
        root.put("connectionCheckUrl",connectionCheckUrl == null ? DEFAULT_CONNECTION_CHECK_URL : connectionCheckUrl);

        if (signer.isConfigured())
            signer.sign(root);

        return root;
    }

    private JSONArray buildWarnings() throws IOException {
        String warningsText = IOUtils.toString(Files.newBufferedReader(new File(Main.resourcesDir, "warnings.json").toPath()));
        JSONArray warnings = JSONArray.fromObject(warningsText);
        return warnings;
    }

    private static void writeToFile(String string, final File file) throws IOException {
        PrintWriter rhpw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
        rhpw.print(string);
        rhpw.close();
    }

    private String prettyPrintJson(JSONObject json) {
        return prettyPrint? json.toString(2): json.toString();
    }

    protected MavenRepository createRepository() throws Exception {

        BaseMavenRepository base = DefaultMavenRepositoryBuilder.getInstance();

        // ensure that we reset plugin filters between batch executions
        base.resetPluginFilters();

        if (javaVersion != null) {
            JavaSpecificationVersion specificationVersion = new JavaSpecificationVersion(this.javaVersion);
            base.addPluginFilter(new JavaVersionPluginFilter(specificationVersion));
            System.out.println("INFO: Filtering plugins for compatibility with Java version " + specificationVersion);
        } else {
            System.out.println("WARNING: Target Java version is not defined, version filters will not be applied");
            //TODO: Default to the version actually supported by the target core if `-cap` is set?
            // base.addPluginFilter(new JavaVersionPluginFilter(JavaVersionUtil.JAVA_8));
        }

        MavenRepository repo = base;
        if (maxPlugins != null)
            repo = new TruncatedMavenRepository(repo,maxPlugins);
        if (experimentalOnly)
            repo = new AlphaBetaOnlyRepository(repo,false);
        if (noExperimental)
            repo = new AlphaBetaOnlyRepository(repo,true);
        if (stableCore) {
            repo = new StableMavenRepository(repo);
        }
        if (capPlugin != null || getCapCore() != null) {
            VersionNumber vp = capPlugin == null ? null : new VersionNumber(capPlugin);
            VersionNumber vc = getCapCore() == null ? ANY_VERSION : new VersionNumber(getCapCore());
            repo = new VersionCappedMavenRepository(repo, vp, vc);
        }
        return repo;
    }

    private JSONObject buildPluginVersions(MavenRepository repository) throws Exception {
        JSONObject plugins = new JSONObject();
        System.err.println("Build plugin versions index from the maven repo...");

        for (PluginHistory plugin : repository.listHudsonPlugins()) {
                System.out.println(plugin.artifactId);

                JSONObject versions = new JSONObject();

                // Gather the plugin properties from the plugin file and the wiki
                for (HPI hpi : plugin.artifacts.values()) {
                    try {
                        JSONObject hpiJson = hpi.toJSON(plugin.artifactId);
                        if (hpiJson == null) {
                            continue;
                        }
                        hpiJson.put("requiredCore", hpi.getRequiredJenkinsVersion());

                        if (hpi.getCompatibleSinceVersion() != null) {
                            hpiJson.put("compatibleSinceVersion",hpi.getCompatibleSinceVersion());
                        }
                        if (hpi.getSandboxStatus() != null) {
                            hpiJson.put("sandboxStatus",hpi.getSandboxStatus());
                        }

                        JSONArray deps = new JSONArray();
                        for (HPI.Dependency d : hpi.getDependencies())
                            deps.add(d.toJSON());
                        hpiJson.put("dependencies",deps);

                        versions.put(hpi.version, hpiJson);
                    } catch (IOException e) {
                        e.printStackTrace();
                        // skip this version
                    }
                }

                plugins.put(plugin.artifactId, versions);
        }
        return plugins;
    }

    /**
     * Build JSON for the plugin list.
     * @param repository
     * @param latest
     */
    protected JSONObject buildPlugins(MavenRepository repository, LatestLinkBuilder latest) throws Exception {

        int validCount = 0;

        JSONObject plugins = new JSONObject();
        ArtifactoryRedirector redirector = null;
        if (downloadFallback != null) {
            redirector = new ArtifactoryRedirector(downloadFallback);
        }
        System.err.println("Gathering list of plugins and versions from the maven repo...");
        for (PluginHistory hpi : repository.listHudsonPlugins()) {
            try {
                System.out.println(hpi.artifactId);

                // Gather the plugin properties from the plugin file and the wiki
                Plugin plugin = new Plugin(hpi);

                pluginToDocumentationUrl.put(plugin.artifactId, plugin.getPluginUrl());

                JSONObject json = plugin.toJSON();
                if (json == null) {
                    System.out.println("Skipping due to lack of checksums: " + plugin.getName());
                    continue;
                }
                System.out.println("=> " + hpi.latest().getGavId());
                plugins.put(plugin.artifactId, json);
                latest.add(plugin.artifactId+".hpi", plugin.latest.getURL().getPath());

                if (download!=null) {
                    for (HPI v : hpi.artifacts.values()) {
                        stage(v, new File(download, "plugins/" + hpi.artifactId + "/" + v.version + "/" + hpi.artifactId + ".hpi"));
                    }
                    if (!hpi.artifacts.isEmpty())
                        createLatestSymlink(hpi, plugin.latest);
                }

                if (wwwDownload!=null) {
                    String permalink = String.format("/latest/%s.hpi", plugin.artifactId);
                    buildIndex(new File(wwwDownload, "plugins/" + hpi.artifactId), hpi.artifactId, hpi.artifacts.values(), permalink);
                }

                if (redirector != null) {
                    for (HPI v : hpi.artifacts.values()) {
                        redirector.recordRedirect(v, "plugins/" + hpi.artifactId + "/" + v.version + "/" + hpi.artifactId + ".hpi");
                    }
                }

                validCount++;
            } catch (IOException e) {
                e.printStackTrace();
                // move on to the next plugin
            }
        }

        if (redirector != null) {
            redirector.writeRedirects();
        }

        if (pluginCountTxt!=null)
            FileUtils.writeStringToFile(pluginCountTxt,String.valueOf(validCount));
        System.err.println("Total " + validCount + " plugins listed.");
        return plugins;
    }

    /**
     * Generates symlink to the latest version.
     */
    protected void createLatestSymlink(PluginHistory hpi, HPI latest) throws InterruptedException, IOException {
        File dir = new File(download, "plugins/" + hpi.artifactId);
        new File(dir,"latest").delete();

        ProcessBuilder pb = new ProcessBuilder();
        pb.command("ln","-s", latest.version, "latest");
        pb.directory(dir);
        int r = pb.start().waitFor();
        if (r !=0)
            throw new IOException("ln failed: "+r);
    }

    /**
     * Stages an artifact into the specified location.
     */
    protected void stage(MavenArtifact a, File dst) throws IOException, InterruptedException {
        File src = a.resolve();
        if (dst.exists() && dst.lastModified()==src.lastModified() && dst.length()==src.length())
            return;   // already up to date

//        dst.getParentFile().mkdirs();
//        FileUtils.copyFile(src,dst);

        // TODO: directory and the war file should have the release timestamp
        dst.getParentFile().mkdirs();

        ProcessBuilder pb = new ProcessBuilder();
        pb.command("ln","-f", src.getAbsolutePath(), dst.getAbsolutePath());
        Process p = pb.start();
        if (p.waitFor()!=0)
            throw new IOException("'ln -f " + src.getAbsolutePath() + " " +dst.getAbsolutePath() +
                    "' failed with code " + p.exitValue() + "\nError: " + IOUtils.toString(p.getErrorStream()) + "\nOutput: " + IOUtils.toString(p.getInputStream()));

    }

    /**
     * Build JSON for the release history list.
     * @param repo
     */
    protected JSONObject buildFullReleaseHistory(MavenRepository repo) throws Exception {
        JSONObject rhRoot = new JSONObject();
        rhRoot.put("releaseHistory", buildReleaseHistory(repo));
        return rhRoot;
    }

    protected JSONArray buildReleaseHistory(MavenRepository repository) throws Exception {

        Calendar oldestDate = new GregorianCalendar();
        oldestDate.add(Calendar.DAY_OF_MONTH, -31);

        JSONArray releaseHistory = new JSONArray();
        System.err.println("Building release history");
        for( Map.Entry<Date,Map<String,HPI>> relsOnDate : repository.listHudsonPluginsByReleaseDate().entrySet() ) {
            String relDate = MavenArtifact.getDateFormat().format(relsOnDate.getKey());
            System.out.println("Releases on " + relDate);
            
            JSONArray releases = new JSONArray();

            for (Map.Entry<String,HPI> rel : relsOnDate.getValue().entrySet()) {
                HPI h = rel.getValue();
                JSONObject o = new JSONObject();
                try {
                    Plugin plugin = new Plugin(h);

                    if (h.getTimestampAsDate().after(oldestDate.getTime())) {
                        String title = plugin.getName();
                        if ((title==null) || (title.equals(""))) {
                            title = h.artifact.artifactId;
                        }

                        o.put("title", title);
                        o.put("wiki", plugin.getPluginUrl());
                    }
                    o.put("gav", h.getGavId());
                    o.put("timestamp", h.getTimestamp());
                    o.put("url", "https://plugins.jenkins.io/" + h.artifact.artifactId);

                    System.out.println("\t" + h.getGavId());
                } catch (IOException e) {
                    System.out.println("Failed to resolve plugin " + h.artifact.artifactId + " so using defaults");
                    o.put("title", h.artifact.artifactId);
                    o.put("wiki", "");
                }

                o.put("version", h.version);

                releases.add(o);
            }
            JSONObject d = new JSONObject();
            d.put("date", relDate);
            d.put("releases", releases);
            releaseHistory.add(d);
        }
        
        return releaseHistory;
    }

    private void buildIndex(File dir, String title, Collection<? extends MavenArtifact> versions, String permalink) throws IOException {
        List<MavenArtifact> list = new ArrayList<MavenArtifact>(versions);
        Collections.sort(list,new Comparator<MavenArtifact>() {
            public int compare(MavenArtifact o1, MavenArtifact o2) {
                return -o1.getVersion().compareTo(o2.getVersion());
            }
        });

        IndexHtmlBuilder index = new IndexHtmlBuilder(dir, title);
        index.add(permalink,"permalink to the latest");
        for (MavenArtifact a : list)
            index.add(a);
        index.close();
    }

    /**
     * Creates a symlink.
     */
    private void ln(String from, File to) throws InterruptedException, IOException {
        to.getParentFile().mkdirs();

        ProcessBuilder pb = new ProcessBuilder();
        pb.command("ln","-sf", from,to.getAbsolutePath());
        if (pb.start().waitFor()!=0)
            throw new IOException("ln failed");
    }

    /**
     * Identify the latest core, populates the htaccess redirect file, optionally download the core wars and build the index.html
     * @return the JSON for the core Jenkins
     */
    protected JSONObject buildCore(MavenRepository repository, LatestLinkBuilder redirect) throws Exception {
        System.err.println("Finding latest Jenkins core WAR...");
        TreeMap<VersionNumber, JenkinsWar> wars = repository.getHudsonWar();
        if (wars.isEmpty())     return null;

        JenkinsWar latest = wars.get(wars.firstKey());
        JSONObject core = latest.toJSON("core");
        System.out.println("core\n=> "+ core);

        redirect.add("jenkins.war", latest.getURL().getPath());

        if (latestCoreTxt !=null)
            writeToFile(latest.getVersion().toString(), latestCoreTxt);

        if (download!=null) {
            // build the download server layout
            for (JenkinsWar w : wars.values()) {
                 stage(w, new File(download,"war/"+w.version+"/"+w.getFileName()));
            }
        }

        if (wwwDownload!=null)
            buildIndex(new File(wwwDownload,"war/"),"jenkins.war", wars.values(), "/latest/jenkins.war");

        return core;
    }

    private static final VersionNumber ANY_VERSION = new VersionNumber("999.999");
}
