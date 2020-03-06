package io.doush.factorio;

import org.jetbrains.annotations.NotNull;
import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.Duration;
import software.amazon.awscdk.core.RemovalPolicy;
import software.amazon.awscdk.services.applicationautoscaling.AdjustmentType;
import software.amazon.awscdk.services.applicationautoscaling.BasicStepScalingPolicyProps;
import software.amazon.awscdk.services.applicationautoscaling.EnableScalingProps;
import software.amazon.awscdk.services.applicationautoscaling.ScalingInterval;
import software.amazon.awscdk.services.cloudwatch.Metric;
import software.amazon.awscdk.services.cloudwatch.MetricProps;
import software.amazon.awscdk.services.ec2.ISecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ecr.IRepository;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.iam.IRole;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.route53.IHostedZone;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.amazon.awscdk.services.s3.BucketProps;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class FactorioServer extends Construct {
    final Bucket bucket;
    final FargateTaskDefinition taskDefinition;
    final EcrImage containerImage;
    final ContainerDefinition container;
    final FargateService service;
    final ScalableTaskCount scalableTaskCount;
    final Metric playersOnline;

    public FactorioServer(@NotNull Construct scope, @NotNull String id,
                          String serverName, String domainName,
                          String version, IHostedZone hostedZone,
                          ISecurityGroup securityGroup, ICluster cluster, IRole executionRole,
                          Role taskRole, IRepository ecrRepo) {
        super(scope, id);


        this.bucket = new Bucket(this, "bucket", BucketProps.builder()
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .encryption(BucketEncryption.S3_MANAGED)
                .removalPolicy(RemovalPolicy.DESTROY)
                .versioned(false)
                .build()
        );

        bucket.grantReadWrite(taskRole);

        this.taskDefinition = new FargateTaskDefinition(this, "taskDefinition",
                FargateTaskDefinitionProps.builder()
                        .cpu(512)
                        .memoryLimitMiB(1024)
                        .executionRole(executionRole)
                        .taskRole(taskRole)
                        .build()
        );

        this.containerImage = ContainerImage.fromEcrRepository(ecrRepo, version);

        this.container = taskDefinition.addContainer("container",
                ContainerDefinitionOptions.builder()
                        .cpu(512)
                        .environment(new TreeMap<>() {{
                            put("S3_BUCKET", bucket.getBucketName());
                            put("HOSTED_ZONE", hostedZone.getHostedZoneId());
                            put("DOMAIN", serverName + ".factorio." + domainName);
                            put("SERVER_NAME", serverName);
                        }})
                        .essential(true)
                        .healthCheck(HealthCheck.builder()
                                .command(List.of("CMD-SHELL", "cat /opt/factorio/server.pid"))
                                .build()
                        )
                        .dockerLabels(Collections.singletonMap(
                                "version", version
                        ))
                        .image(containerImage)
                        .logging(LogDriver.awsLogs(AwsLogDriverProps.builder()
                                .streamPrefix("factorio-" + serverName)
                                .logRetention(RetentionDays.ONE_WEEK)
                                .build())
                        )
                        .build()
        );

        container.addPortMappings(PortMapping.builder()
                .containerPort(34197)
                .hostPort(34197)
                .protocol(Protocol.UDP)
                .build()
        );

        container.addPortMappings(PortMapping.builder()
                .containerPort(27015)
                .hostPort(27015)
                .protocol(Protocol.TCP)
                .build()
        );

        this.service = new FargateService(this, "service", FargateServiceProps.builder()
                .assignPublicIp(true)
                .cluster(cluster)
                .desiredCount(0)
                .minHealthyPercent(100)
                .maxHealthyPercent(200)
                .serviceName("factorio-" + serverName)
                .securityGroup(securityGroup)
                .vpcSubnets(SubnetSelection.builder().onePerAz(true).subnetType(SubnetType.PUBLIC).build())
                .deploymentController(DeploymentController.builder().type(DeploymentControllerType.ECS).build())
                .platformVersion(FargatePlatformVersion.LATEST)
                .taskDefinition(taskDefinition)
                .build()
        );

        this.scalableTaskCount = service.autoScaleTaskCount(EnableScalingProps.builder()
                .minCapacity(0)
                .maxCapacity(1)
                .build()
        );

        this.playersOnline = new Metric(MetricProps.builder()
                .metricName("PlayersOnline")
                .namespace("Factorio-" + serverName)
                .period(Duration.minutes(15))
                .build()
        );

        scalableTaskCount.scaleOnMetric("scaleOnMetric", BasicStepScalingPolicyProps.builder()
                .adjustmentType(AdjustmentType.EXACT_CAPACITY)
                .metric(playersOnline)
                .scalingSteps(List.of(
                        ScalingInterval.builder().lower(0).upper(0).change(0).build(),
                        ScalingInterval.builder().lower(1).change(1).build()
                ))
                .build()
        );
    }
}
