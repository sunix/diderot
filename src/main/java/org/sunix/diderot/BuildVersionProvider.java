package org.sunix.diderot;

import org.eclipse.microprofile.config.ConfigProvider;

import picocli.CommandLine.IVersionProvider;

/**
 * Reports the version Maven actually built, rather than a string hand-maintained in an
 * annotation. Release automation bumps the pom; a hardcoded literal would quietly keep
 * claiming the old number in every published binary.
 */
public class BuildVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        String version = ConfigProvider.getConfig()
                .getOptionalValue("quarkus.application.version", String.class)
                .orElse("unknown");
        return new String[] { "diderot " + version };
    }
}
