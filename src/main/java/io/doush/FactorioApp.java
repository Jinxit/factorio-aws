package io.doush;

import org.jetbrains.annotations.NotNull;
import software.amazon.awscdk.core.App;
import software.amazon.awscdk.core.Environment;
import software.amazon.awscdk.core.StackProps;

import java.util.Optional;

public class FactorioApp {
    public static void main(final String[] args) {
        App app = new App();

        var serverName = getContextString(app, "serverName");
        var domainName = getContextString(app, "domainName");
        var subDomain = getContextString(app, "subDomain");
        var version = getContextString(app, "version");
        var region = getContextString(app, "region");

        new FactorioStack(app, serverName, domainName, subDomain, version,
                StackProps.builder()
                        .env(Environment.builder()
                                .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                                .region(region)
                                .build()
                        )
                        .build()
        );

        app.synth();
    }

    @NotNull
    private static String getContextString(App app, String key) {
        return (String) Optional.ofNullable(app.getNode().tryGetContext(key)).orElseThrow();
    }
}
