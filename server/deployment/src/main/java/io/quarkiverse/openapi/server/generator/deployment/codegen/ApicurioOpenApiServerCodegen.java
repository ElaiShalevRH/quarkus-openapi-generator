package io.quarkiverse.openapi.server.generator.deployment.codegen;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.eclipse.microprofile.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.quarkiverse.openapi.server.generator.deployment.CodegenConfig;
import io.quarkus.bootstrap.prebuild.CodeGenException;
import io.quarkus.deployment.CodeGenContext;
import io.quarkus.deployment.CodeGenProvider;

public class ApicurioOpenApiServerCodegen implements CodeGenProvider {

    private static final Logger log = LoggerFactory.getLogger(ApicurioCodegenWrapper.class);

    @Override
    public String providerId() {
        return "jaxrs";
    }

    @Override
    public String[] inputExtensions() {
        return new String[] { "json", "yaml", "yml" };
    }

    @Override
    public String inputDirectory() {
        return "resources";
    }

    private Path getInputBaseDir(final Path sourceDir, final Config config) {
        return config.getOptionalValue(CodegenConfig.getInputBaseDirPropertyName(), String.class)
                .map(inputBaseDir -> {
                    int srcIndex = sourceDir.toString().lastIndexOf("src");
                    return Path.of(sourceDir.toString().substring(0, srcIndex), inputBaseDir);
                }).orElse(Path.of(sourceDir.toString(), "openapi"));
    }

    @Override
    public boolean shouldRun(Path sourceDir, Config config) {
        if (config.getOptionalValue(CodegenConfig.getSpecPropertyName(), String.class).isEmpty()) {
            return false;
        }
        Path path = getInputBaseDir(sourceDir, config);
        return Files.isDirectory(path);
    }

    @Override
    public boolean trigger(CodeGenContext context) throws CodeGenException {
        final Path openApiDir = getInputBaseDir(context.inputDir(), context.config());
        final Path outDir = context.outDir();
        final ApicurioCodegenWrapper apicurioCodegenWrapper = new ApicurioCodegenWrapper(
                context.config(), outDir.toFile());
        final String specPropertyName = context.config()
                .getOptionalValue(CodegenConfig.getSpecPropertyName(), String.class)
                .orElseThrow();
        final File openApiResource = new File(openApiDir.toFile(), specPropertyName);
        if (!openApiResource.exists()) {
            throw new CodeGenException(
                    "Specification file not found: " + openApiResource.getAbsolutePath());
        }
        if (!openApiResource.isFile()) {
            throw new CodeGenException(
                    "Specification file is not a file: " + openApiResource.getAbsolutePath());
        }
        if (!openApiResource.canRead()) {
            throw new CodeGenException(
                    "Specification file is not readable: " + openApiResource.getAbsolutePath());
        }
        if (Arrays.stream(this.inputExtensions()).noneMatch(specPropertyName::endsWith)) {
            throw new CodeGenException(
                    "Specification file must have one of the following extensions: " + Arrays.toString(
                            this.inputExtensions()));
        }
        // Apicurio only supports JSON => convert yaml to JSON
        final File jsonSpec = specPropertyName.endsWith("json") ? openApiResource
                : convertToJSON(openApiResource.toPath());
        try {
            apicurioCodegenWrapper.generate(jsonSpec.toPath());
        } catch (CodeGenException e) {
            log.warn("Exception found processing specification with name: {}",
                    openApiResource.getAbsolutePath());
        }
        return true;
    }

    private File convertToJSON(Path yamlPath) throws CodeGenException {
        try {
            LoaderOptions loaderOptions = new LoaderOptions();
            loaderOptions.setCodePointLimit(12 * 1024 * 1024); // 12 MB
            YAMLFactory yamlFactory = YAMLFactory.builder()
                    .loaderOptions(loaderOptions)
                    .build();
            ObjectMapper yamlReader = new ObjectMapper(yamlFactory);
            Object obj = yamlReader.readValue(yamlPath.toFile(), Object.class);
            ObjectMapper jsonWriter = new ObjectMapper();
            File jsonFile = File.createTempFile(yamlPath.toFile().getName(), ".json");
            jsonFile.deleteOnExit();
            jsonWriter.writeValue(jsonFile, obj);
            return jsonFile;
        } catch (Exception e) {
            throw new CodeGenException("Error converting YAML to JSON", e);
        }
    }
}
