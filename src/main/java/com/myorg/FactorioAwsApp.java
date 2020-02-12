package com.myorg;

import software.amazon.awscdk.core.App;

import java.util.Arrays;

public class FactorioAwsApp {
    public static void main(final String[] args) {
        App app = new App();

        new FactorioAwsStack(app, "FactorioAwsStack");

        app.synth();
    }
}
