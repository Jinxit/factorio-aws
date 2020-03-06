package io.doush.factorio;

import software.amazon.awscdk.core.*;
import software.amazon.awscdk.services.ec2.Vpc;

public class FactorioStack extends Stack {
    public FactorioStack(final Construct scope, String id, final StackProps props, String domainName) {
        super(scope, id, props);

        var vpc = Vpc.Builder.create(this, "vpc")
                .cidr(Vpc.DEFAULT_CIDR_RANGE)
                .subnetConfiguration(Vpc.DEFAULT_SUBNETS)
                .maxAzs(3)
                .build();

        new FactorioCluster(this, "factorio-cluster", domainName, vpc, this.getRegion(), this.getAccount());
    }
}
