/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.io.sstable;

import com.google.common.base.CharMatcher;
import com.google.common.base.Objects;
import org.apache.cassandra.io.sstable.format.SSTableFormat;
import org.apache.cassandra.io.sstable.format.Version;
import org.apache.cassandra.io.sstable.metadata.IMetadataSerializer;
import org.apache.cassandra.io.sstable.metadata.MetadataSerializer;
import org.apache.cassandra.utils.Pair;
import org.apache.directory.api.util.Strings;
import org.apache.hadoop.conf.Configuration;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

import static org.apache.cassandra.io.sstable.Component.separator;

/**
 * A SSTable is described by the keyspace and column family it contains data
 * for, a generation (where higher generations contain more recent data) and
 * an alphabetic version string.
 *
 * A descriptor can be marked as temporary, which influences generated filenames.
 */
public class Descriptor
{
    public static String TMP_EXT = ".tmp";
    private static String EMPTY_STRING = "";

    /** canonicalized path to the directory where SSTable resides */
    public final String directory;
    /** version has the following format: <code>[a-z]+</code> */
    public final Version version;
    public final String ksname;
    public final String cfname;
    public final int generation;
    public final SSTableFormat.Type formatType;
    /** digest component - might be {@code null} for old, legacy sstables */
    public final Component digestComponent;
    private final int hashCode;
    private Configuration conf;


    public Descriptor(String version, String directory, String ksname, String cfname, int generation,
                      SSTableFormat.Type formatType, Configuration configuration)
    {
        this(formatType.info.getVersion(version), directory, ksname, cfname, generation, formatType,
                Component.digestFor(formatType.info.getLatestVersion().uncompressedChecksumType()),
                configuration);
    }

    public Descriptor(Version version, String directory, String ksname, String cfname, int generation,
                      SSTableFormat.Type formatType, Component digestComponent, Configuration configuration)
    {
        assert version != null && directory != null && ksname != null && cfname != null &&
                formatType.info.getLatestVersion().getClass().equals(version.getClass());

        this.version = version;
        this.directory = directory;
        this.ksname = ksname;
        this.cfname = cfname;
        this.generation = generation;
        this.formatType = formatType;
        this.digestComponent = digestComponent;
        this.conf = configuration;

        hashCode = Objects.hashCode(version, this.directory, generation, ksname, cfname, formatType);
    }

    public Descriptor withGeneration(int newGeneration)
    {
        return new Descriptor(version, directory, ksname, cfname, newGeneration, formatType, digestComponent, conf);
    }

    public Descriptor withFormatType(SSTableFormat.Type newType)
    {
        return new Descriptor(newType.info.getLatestVersion(), directory, ksname, cfname, generation, newType,
                digestComponent, conf);
    }

    public Descriptor withDigestComponent(Component newDigestComponent)
    {
        return new Descriptor(version, directory, ksname, cfname, generation, formatType,
                newDigestComponent, conf);
    }

    public String tmpFilenameFor(Component component)
    {
        return filenameFor(component) + TMP_EXT;
    }

    public String filenameFor(Component component)
    {
        return baseFilename() + separator + component.name();
    }

    public String baseFilename()
    {
        StringBuilder buff = new StringBuilder();
        buff.append(directory).append(File.separatorChar);
        appendFileName(buff);
        return buff.toString();
    }

    private void appendFileName(StringBuilder buff)
    {
        if (!version.hasNewFileName())
        {
            buff.append(ksname).append(separator);
            buff.append(cfname).append(separator);
        }
        buff.append(version).append(separator);
        buff.append(generation);
        if (formatType != SSTableFormat.Type.LEGACY)
            buff.append(separator).append(formatType.name);
    }

    public String relativeFilenameFor(Component component)
    {
        final StringBuilder buff = new StringBuilder();
        appendFileName(buff);
        buff.append(separator).append(component.name());
        return buff.toString();
    }

    public SSTableFormat getFormat()
    {
        return formatType.info;
    }

    public Configuration getConfiguration() { return this.conf; }

    /**
     *  Files obsoleted by CASSANDRA-7066 : temporary files and compactions_in_progress. We support
     *  versions 2.1 (ka) and 2.2 (la).
     *  Temporary files have tmp- or tmplink- at the beginning for 2.2 sstables or after ks-cf- for 2.1 sstables
     */

    private final static String LEGACY_COMP_IN_PROG_REGEX_STR = "^compactions_in_progress(\\-[\\d,a-f]{32})?$";
    private final static Pattern LEGACY_COMP_IN_PROG_REGEX = Pattern.compile(LEGACY_COMP_IN_PROG_REGEX_STR);
    private final static String LEGACY_TMP_REGEX_STR = "^((.*)\\-(.*)\\-)?tmp(link)?\\-((?:l|k).)\\-(\\d)*\\-(.*)$";
    private final static Pattern LEGACY_TMP_REGEX = Pattern.compile(LEGACY_TMP_REGEX_STR);

    public static boolean isValidFile(String fileName)
    {
        return fileName.endsWith(".db") && !LEGACY_TMP_REGEX.matcher(fileName).matches();
    }

    /**
     * @param filename The SSTable filename
     * @return Descriptor of the SSTable initialized from filename
     */
    public static Descriptor fromFilename(String filename, Configuration configuration)
    {
        return fromFilename(filename, false, configuration);
    }

    public static Descriptor fromFilename(String filename, SSTableFormat.Type formatType, Configuration configuration)
    {
        return fromFilename(filename, configuration).withFormatType(formatType);
    }

    private static String getParentDir(String filePath) {
        if (Strings.isEmpty(filePath))
            return EMPTY_STRING;

        return filePath.substring(0, filePath.lastIndexOf(File.separatorChar));
    }

    public static Descriptor fromFilename(String filename, boolean skipComponent, Configuration configuration)
    {
        return fromFilename(getParentDir(filename), getName(filename), skipComponent, configuration).left;
    }

    private static String getName(String path) {
        int index = path.lastIndexOf(File.separatorChar);
        //if (index < prefixLength) return path.substring(prefixLength);
        if (index == -1)
            return EMPTY_STRING;

        return path.substring(index + 1);
    }

    /**
     * Filename of the form is vary by version:
     *
     * <ul>
     *     <li>&lt;ksname&gt;-&lt;cfname&gt;-(tmp-)?&lt;version&gt;-&lt;gen&gt;-&lt;component&gt; for cassandra 2.0
     *     and before</li>
     *     <li>(&lt;tmp marker&gt;-)?&lt;version&gt;-&lt;gen&gt;-&lt;component&gt; for cassandra 3.0 and later</li>
     * </ul>
     *
     * If this is for SSTable of secondary index, directory should ends with index name for 2.1+.
     *
     * @param directory The directory of the SSTable files
     * @param name The name of the SSTable file
     * @param skipComponent true if the name param should not be parsed for a component tag
     *
     * @return A Descriptor for the SSTable, and the Component remainder.
     */
    public static Pair<Descriptor, String> fromFilename(String directory, String name,
                                                        boolean skipComponent, Configuration configuration)
    {
        //File parentDirectory = directory != null ? directory : new File(".");
        String parentDirectory = directory;

        // tokenize the filename
        StringTokenizer st = new StringTokenizer(name, String.valueOf(separator));
        String nexttok;

        // read tokens backwards to determine version
        Deque<String> tokenStack = new ArrayDeque<>();
        while (st.hasMoreTokens())
        {
            tokenStack.push(st.nextToken());
        }

        // component suffix
        String component = skipComponent ? null : tokenStack.pop();

        nexttok = tokenStack.pop();
        // generation OR format type
        SSTableFormat.Type fmt = SSTableFormat.Type.LEGACY;
        if (!CharMatcher.DIGIT.matchesAllOf(nexttok))
        {
            fmt = SSTableFormat.Type.validate(nexttok);
            nexttok = tokenStack.pop();
        }

        // generation
        int generation = Integer.parseInt(nexttok);

        // version
        nexttok = tokenStack.pop();

        if (!Version.validate(nexttok))
            throw new UnsupportedOperationException("SSTable " + name + " is too old to open.  Upgrade to 2.0 " +
                    "first, and run upgradesstables");

        Version version = fmt.info.getVersion(nexttok);

        // ks/cf names
        String cfname = getName(directory);
        String ksname = getName(getParentDir(directory));


        if (!version.hasNewFileName())
        {
            cfname = tokenStack.pop();
            ksname = tokenStack.pop();
        }
        assert tokenStack.isEmpty() : "Invalid file name " + name + " in " + directory;

        return Pair.create(new Descriptor(version, parentDirectory, ksname, cfname, generation, fmt,
                                          // _assume_ version from version
                                          Component.digestFor(version.uncompressedChecksumType()),
                                          configuration),
                           component);
    }

    public IMetadataSerializer getMetadataSerializer()
    {
        return new MetadataSerializer();
    }

    /**
     * @return true if the current Cassandra version can read the given sstable version
     */
    public boolean isCompatible()
    {
        return version.isCompatible();
    }

    @Override
    public String toString()
    {
        return baseFilename();
    }

    @Override
    public boolean equals(Object o)
    {
        if (o == this)
            return true;
        if (!(o instanceof Descriptor))
            return false;
        Descriptor that = (Descriptor)o;
        return that.directory.equals(this.directory)
                       && that.generation == this.generation
                       && that.ksname.equals(this.ksname)
                       && that.cfname.equals(this.cfname)
                       && that.formatType == this.formatType;
    }

    @Override
    public int hashCode()
    {
        return hashCode;
    }
}
