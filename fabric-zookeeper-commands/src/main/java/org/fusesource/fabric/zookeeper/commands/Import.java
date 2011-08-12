/**
 * Copyright (C) 2011, FuseSource Corp.  All rights reserved.
 * http://fusesource.com
 *
 * The software in this package is published under the terms of the
 * CDDL license a copy of which has been included with this distribution
 * in the license.txt file.
 */
package org.fusesource.fabric.zookeeper.commands;

import org.apache.felix.gogo.commands.Argument;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.Option;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

@Command(name = "import", scope = "zk", description = "Import data into zookeeper")
public class Import extends ZooKeeperCommandSupport {

    @Argument(description = "Location of the file or filesystem to load")
    String source = "." + File.separator + "import";

    @Option(name="-d", aliases={"--delete"}, description="Delete any paths not in the tree being imported (CAUTION!)")
    boolean delete = false;

    @Option(name="-t", aliases={"--target"}, description="Target location in ZooKeeper tree to import to")
    String target = "/";

    @Option(name="-props", aliases={"--properties"}, description="Argument is URL pointing to a properties file")
    boolean properties = false;

    @Option(name="-fs", aliases={"--filesystem"}, description="Argument is the top level directory of a local filesystem tree")
    boolean filesystem = true;

    @Option(name="-f", aliases={"--regex"}, description="regex to filter on what paths to import", multiValued=true)
    String regex[];

    @Override
    protected Object doExecute() throws Exception {
        if (properties == true) {
            filesystem = false;
        }
        if (filesystem == true) {
            properties = false;
        }
        if (properties) {
            readPropertiesFile();
        }
        if (filesystem) {
            readFileSystem();
        }
        System.out.println("Successfully imported settings from " + source);
        return null;
    }

    private String stripPath(String path) {
        String strs[] = path.split(source);
        if (strs.length == 0) {
            return "";
        }
        return strs[strs.length - 1].substring(0, strs[1].length() - ".cfg".length());
    }

    private String buildZKPath(File parent, File current) {
        String rc = "";
        if (current != null && !parent.equals(current)) {
            rc = buildZKPath(parent, current.getParentFile()) + "/" + current.getName();
        }
        return rc;
    }

    private void getCandidates(File parent, File current, Map<String, String> settings) throws Exception {
        if (current.isDirectory()) {
            for (File child : current.listFiles(new FileFilter() {
                @Override
                public boolean accept(File file) {
                    if (file.isDirectory() || file.getName().endsWith(".cfg")) {
                        return true;
                    }
                    return false;
                }
            })) {
                getCandidates(parent, child, settings);
            }
            String p = buildZKPath(parent, current).replaceFirst("/", "");
            settings.put(p, null);
        } else {
            BufferedInputStream in = new BufferedInputStream(new FileInputStream(current));
            byte[] contents = new byte[in.available()];
            in.read(contents);
            in.close();
            String p = buildZKPath(parent, current).replaceFirst("/", "");
            if (p.endsWith(".cfg")) {
                p = p.substring(0, p.length() - ".cfg".length());
            }
            settings.put(p, new String(contents));
        }
    }

    private void readFileSystem() throws Exception {
        Map<String, String> settings = new TreeMap<String, String>();
        File s = new File(source);
        getCandidates(s, s, settings);
        List<Pattern> patterns = RegexSupport.getPatterns(regex);

        if (!target.endsWith("/")) {
            target = target + "/";
        }
        if (!target.startsWith("/")) {
            target = "/" + target;
        }

        List<String> paths = new ArrayList<String>();

        for(String key : settings.keySet()) {
            if (!RegexSupport.matches(patterns, key)) {
                continue;
            }
            String data = settings.get(key);
            key = target + key;
            paths.add(key);
            getZooKeeper().createOrSetWithParents(key, data, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }

        if (delete) {
            deletePathsNotIn(paths);
        }
    }

    private void deletePathsNotIn(List<String> paths) throws Exception {
        List<String> zkPaths = getZooKeeper().getAllChildren(target);

        for (String path : zkPaths) {
            path = "/" + path;
            if (!paths.contains(path)) {
                getZooKeeper().deleteWithChildren(path);
            }
        }

    }

    private void readPropertiesFile() throws Exception {
        List<Pattern> patterns = RegexSupport.getPatterns(regex);
        InputStream in = new BufferedInputStream(new URL(source).openStream());
        List<String> paths = new ArrayList<String>();
        Properties props = new Properties();
        props.load(in);
        for (Enumeration names = props.propertyNames(); names.hasMoreElements();) {
            String name = (String) names.nextElement();
            String value = props.getProperty(name);
            if (value != null && value.isEmpty()) {
                value = null;
            }
            if (!name.startsWith("/")) {
                name = "/" + name;
            }
            if (!RegexSupport.matches(patterns, name)) {
                continue;
            }
            name = target + name;
            paths.add(name);
            getZooKeeper().createOrSetWithParents(name, value, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        if (delete) {
            deletePathsNotIn(paths);
        }
    }

}
