package io.doush.factorio;

import software.amazon.awscdk.core.*;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;

import java.util.List;

public class FactorioStack extends Stack {
    public FactorioStack(final Construct scope, String id, final StackProps props, String domainName) {
        super(scope, id, props);

        var vpc = Vpc.Builder.create(this, "vpc")
                .cidr("10.1.0.0/16")
                .subnetConfiguration(List.of(
                        SubnetConfiguration.builder()
                                .cidrMask(19)
                                .name("Public")
                                .subnetType(SubnetType.PUBLIC)
                                .build()
                ))
                .maxAzs(2)
                .build();

        new FactorioCluster(this, "factorio-cluster", domainName, vpc, this.getRegion(), this.getAccount());
    }
}
