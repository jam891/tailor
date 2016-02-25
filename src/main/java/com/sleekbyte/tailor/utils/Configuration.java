package com.sleekbyte.tailor.utils;

import com.sleekbyte.tailor.common.ColorSettings;
import com.sleekbyte.tailor.common.ConstructLengths;
import com.sleekbyte.tailor.common.Messages;
import com.sleekbyte.tailor.common.Rules;
import com.sleekbyte.tailor.common.Severity;
import com.sleekbyte.tailor.common.YamlConfiguration;
import com.sleekbyte.tailor.format.Format;
import com.sleekbyte.tailor.format.Format.IllegalFormatException;
import com.sleekbyte.tailor.format.Formatter;
import com.sleekbyte.tailor.utils.CLIArgumentParser.CLIArgumentParserException;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.ParseException;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Adaptor class for YamlConfiguration and CLIArgumentParser.
 */
public final class Configuration {

    private static CLIArgumentParser CLIArgumentParser = new CLIArgumentParser();
    private Optional<YamlConfiguration> yamlConfiguration;
    private CommandLine cmd;

    public Configuration(String[] args) throws ParseException, IOException {
        cmd = CLIArgumentParser.parseCommandLine(args);
        yamlConfiguration = YamlConfigurationFileManager.getConfiguration(CLIArgumentParser.getConfigFilePath());
    }

    public boolean shouldPrintHelp() {
        return CLIArgumentParser.shouldPrintHelp();
    }

    public boolean shouldPrintVersion() {
        return CLIArgumentParser.shouldPrintVersion();
    }

    public boolean shouldPrintRules() {
        return CLIArgumentParser.shouldPrintRules();
    }

    public boolean shouldListFiles() {
        return CLIArgumentParser.shouldListFiles();
    }

    public boolean shouldColorOutput() {
        return CLIArgumentParser.shouldColorOutput();
    }

    public boolean shouldInvertColorOutput() {
        return CLIArgumentParser.shouldInvertColorOutput();
    }

    public boolean debugFlagSet() throws CLIArgumentParserException {
        return CLIArgumentParser.debugFlagSet();
    }

    /**
     * Collects all rules enabled by default and then filters out rules according to CLI options
     * and YamlConfiguration file.
     *
     * @return list of enabled rules after filtering
     * @throws CLIArgumentParserException if rule names specified in command line options are not valid
     */
    public Set<Rules> getEnabledRules() throws CLIArgumentParserException {
        // --only is given precedence over --except
        // CLI input is given precedence over YAML configuration file

        // Retrieve included or excluded rules from CLI
        Set<String> onlySpecificRules = CLIArgumentParser.getOnlySpecificRules();
        if (onlySpecificRules.size() > 0) {
            return getRulesFilteredByOnly(onlySpecificRules);
        }

        Set<String> excludedRules = CLIArgumentParser.getExcludedRules();
        if (excludedRules.size() > 0) {
            return getRulesFilteredByExcept(excludedRules);
        }

        // Retrieve included or excluded rules from YAML configuration
        if (yamlConfiguration.isPresent()) {
            YamlConfiguration configuration = yamlConfiguration.get();
            onlySpecificRules = configuration.getOnly();
            if (onlySpecificRules.size() > 0) {
                return getRulesFilteredByOnly(onlySpecificRules);
            }

            excludedRules = configuration.getExcept();
            if (excludedRules.size() > 0) {
                return getRulesFilteredByExcept(excludedRules);
            }
        }

        // If `only`/`except` options aren't used then enable all rules
        return new HashSet<>(Arrays.asList(Rules.values()));
    }

    /**
     * Iterate through pathNames and derive swift source files from each path.
     *
     * @return Swift file names
     * @throws IOException if path specified does not exist
     */
    public Set<String> getFilesToAnalyze() throws IOException, CLIArgumentParserException {
        Optional<String> srcRoot = getSrcRoot();
        List<String> pathNames = new ArrayList<>();
        String[] cliPaths = cmd.getArgs();

        if (cliPaths.length >= 1) {
            pathNames.addAll(Arrays.asList(cliPaths));
        }
        Set<String> fileNames = new TreeSet<>();

        if (pathNames.size() >= 1) {
            fileNames.addAll(findFilesInPaths(pathNames));
        } else if (yamlConfiguration.isPresent()) {
            YamlConfiguration config = yamlConfiguration.get();
            Optional<String> configFileLocation = config.getFileLocation();
            if (configFileLocation.isPresent() && getFormat() == Format.XCODE) {
                System.out.println(Messages.TAILOR_CONFIG_LOCATION + configFileLocation.get());
            }
            URI rootUri = new File(srcRoot.orElse(".")).toURI();
            Finder finder = new Finder(config.getInclude(), config.getExclude(), rootUri);
            Files.walkFileTree(Paths.get(rootUri), finder);
            fileNames.addAll(finder.getFileNames().stream().collect(Collectors.toList()));
        } else if (srcRoot.isPresent()) {
            pathNames.add(srcRoot.get());
            fileNames.addAll(findFilesInPaths(pathNames));
        }

        return fileNames;
    }

    public ConstructLengths parseConstructLengths() throws CLIArgumentParserException {
        return CLIArgumentParser.parseConstructLengths();
    }

    public Severity getMaxSeverity() throws CLIArgumentParserException {
        return CLIArgumentParser.getMaxSeverity();
    }

    public String getXcodeprojPath() {
        return CLIArgumentParser.getXcodeprojPath();
    }

    public void printHelp() {
        CLIArgumentParser.printHelp();
    }

    /**
     * Get an instance of the formatter specified by the user.
     * @param colorSettings the command-line color settings
     * @return formatter instance that implements Formatter interface
     * @throws CLIArgumentParserException if the user-specified format does not correspond to a supported type
     */
    public Formatter getFormatter(ColorSettings colorSettings) throws CLIArgumentParserException, YAMLException {
        String formatClass = getFormat().getClassName();
        Formatter formatter;
        try {
            Constructor formatConstructor = Class.forName(formatClass).getConstructor(ColorSettings.class);
            formatter = (Formatter) formatConstructor.newInstance(colorSettings);
        } catch (ReflectiveOperationException e) {
            throw new CLIArgumentParserException("Formatter was not successfully created: " + e);
        }
        return formatter;
    }

    /**
     * Retrieves format from CLI or YAML configuration file.
     * Returns the XCODE format by default if no format is found.
     *
     * @return format from CLI or YAML configuration file.  XCODE format by default.
     * @throws CLIArgumentParserException error when parsing the format from CLI arguments
     * @throws YAMLException error when parsing the format from YAML configuration file
     */
    private Format getFormat() throws CLIArgumentParserException, YAMLException {
        // Try to get format from CLI/config file. Else use the default format (XCODE)
        Format format = Format.XCODE;
        if (CLIArgumentParser.formatOptionSet()) {
            format = CLIArgumentParser.getFormat();
        } else if (yamlConfiguration.isPresent() && !yamlConfiguration.get().getFormat().isEmpty()) {
            try {
                format = Format.parseFormat(yamlConfiguration.get().getFormat());
            } catch (IllegalFormatException e) {
                throw new YAMLException(Messages.INVALID_OPTION_VALUE
                    + "format ." + " Options are <" + Format.getFormats() + ">.");
            }
        }
        return format;
    }

    private static Set<String> findFilesInPaths(List<String> pathNames) throws IOException {
        Set<String> fileNames = new HashSet<>();
        for (String pathName : pathNames) {
            File file = new File(pathName);
            if (file.isDirectory()) {
                Files.walk(Paths.get(pathName))
                    .filter(path -> path.toString().endsWith(".swift"))
                    .filter(path -> {
                            File tempFile = path.toFile();
                            return tempFile.isFile() && tempFile.canRead();
                        })
                    .forEach(path -> fileNames.add(path.toString()));
            } else if (file.isFile() && pathName.endsWith(".swift") && file.canRead()) {
                fileNames.add(pathName);
            }
        }
        return fileNames;
    }

    /**
     * Checks environment variable SRCROOT (set by Xcode) for the top-level path to the source code and adds path to
     * pathNames.
     */
    private static Optional<String> getSrcRoot() {
        String srcRoot = System.getenv("SRCROOT");
        if (srcRoot == null || srcRoot.equals("")) {
            return Optional.empty();
        }
        return Optional.of(srcRoot);
    }

    /**
     * Checks if rules specified in command line option is valid.
     *
     * @param enabledRules   all valid rule names
     * @param specifiedRules rule names specified from command line
     * @throws CLIArgumentParserException if rule name specified in command line is not valid
     */
    private static void checkValidRules(Set<String> enabledRules, Set<String> specifiedRules)
        throws CLIArgumentParserException {
        if (!enabledRules.containsAll(specifiedRules)) {
            specifiedRules.removeAll(enabledRules);
            throw new CLIArgumentParserException("The following rules were not recognized: " + specifiedRules);
        }
    }

    private Set<Rules> getRulesFilteredByOnly(Set<String> parsedRules) throws CLIArgumentParserException {
        Set<Rules> enabledRules = new HashSet<>(Arrays.asList(Rules.values()));
        Set<String> enabledRuleNames = enabledRules.stream().map(Rules::getName).collect(Collectors.toSet());
        Configuration.checkValidRules(enabledRuleNames, parsedRules);
        return enabledRules.stream().filter(rule -> parsedRules.contains(rule.getName())).collect(Collectors.toSet());
    }

    private Set<Rules> getRulesFilteredByExcept(Set<String> parsedRules) throws CLIArgumentParserException {
        Set<Rules> enabledRules = new HashSet<>(Arrays.asList(Rules.values()));
        Set<String> enabledRuleNames = enabledRules.stream().map(Rules::getName).collect(Collectors.toSet());
        Configuration.checkValidRules(enabledRuleNames, parsedRules);
        return enabledRules.stream().filter(rule -> !parsedRules.contains(rule.getName())).collect(Collectors.toSet());
    }

}
