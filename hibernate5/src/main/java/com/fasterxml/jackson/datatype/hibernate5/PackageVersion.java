package com.fasterxml.jackson.datatype.hibernate5;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.core.Versioned;
import com.fasterxml.jackson.core.util.VersionUtil;

/**
 * Automatically generated from PackageVersion.java.in during
 * packageVersion-generate execution of maven-replacer-plugin in pom.xml.
 */
public final class PackageVersion implements Versioned {
    private static final Logger logger    = LoggerFactory.getLogger(PackageVersion.class);
    private static final String PROP_FILE = "package-version.properties";
    public static final Version VERSION;
    static {
        Properties prop = new Properties();
        InputStream is = null;
        try {
            is = PackageVersion.class.getResourceAsStream(PROP_FILE);
            if (is != null) {
                prop.load(is);
            }
        } catch (IOException e) {
            logger.error("Unable to load {}", PROP_FILE);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    //ignore
                }
            }
        }
        VERSION = VersionUtil.parseVersion(prop.getProperty("version", "unknown_version"), prop.getProperty("group", "unknown_group"), prop.getProperty("artifactId", "unknown_artifact"));
    }

    @Override
    public Version version() {
        return VERSION;
    }
}
