package io.doush.factorio;

import org.jetbrains.annotations.NotNull;
import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.Environment;
import software.amazon.awscdk.core.StackProps;

import java.util.Optional;

public class FactorioApp {
    public static void main(final String[] args) {
        App app = new App();

        var domainName = getContextString(app, "domainName");

        var environment = Environment.builder()
                .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                .region(System.getenv("CDK_DEFAULT_REGION"))
                .build();
        new FactorioStack(app, "factorio-stack-" + domainName.replaceAll("\\.", "-"),
                StackProps.builder()
                        .env(environment)
                        .build(),
                domainName
        );

        app.synth();
    }

    @NotNull
    private static String getContextString(App app, String key) {
        var str = (String) Optional.ofNullable(app.getNode().tryGetContext(key)).orElseThrow();
        if (str.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing context value " + key);
        }
        return str;
    }
}
