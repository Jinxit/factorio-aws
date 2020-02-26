package io.doush;

import software.amazon.awscdk.core.*;
import software.amazon.awscdk.services.applicationautoscaling.AdjustmentType;
import software.amazon.awscdk.services.applicationautoscaling.BasicStepScalingPolicyProps;
import software.amazon.awscdk.services.applicationautoscaling.EnableScalingProps;
import software.amazon.awscdk.services.applicationautoscaling.ScalingInterval;
import software.amazon.awscdk.services.cloudwatch.Metric;
import software.amazon.awscdk.services.cloudwatch.MetricProps;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ec2.VpcLookupOptions;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.RoleProps;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.route53.HostedZone;
import software.amazon.awscdk.services.route53.HostedZoneProviderProps;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.amazon.awscdk.services.s3.BucketProps;

import java.util.List;
import java.util.Map;

public class FactorioStack extends Stack {
    public FactorioStack(final Construct scope, final String serverName, final String domainName,
                         final String subDomain,
                         final String version, final StackProps props) {
        super(scope, "factorio-" + serverName, props);

        var hostedZone = HostedZone.fromLookup(this, "hostedZone", HostedZoneProviderProps.builder()
                .domainName(domainName)
                .build()
        );

        var vpc = Vpc.fromLookup(this, "vpc", VpcLookupOptions.builder()
                .vpcId("vpc-016c02e60ed3582ad")
                .build()
        );
        var securityGroup = SecurityGroup.fromSecurityGroupId(this, "securityGroup",
                "sg-0b5755e882ace39cc");
        var cluster = Cluster.fromClusterAttributes(this, "cluster",
                ClusterAttributes.builder()
                        .clusterArn("arn:aws:ecs:eu-north-1:808354942258")
                        .clusterName("factorio")
                        .vpc(vpc)
                        .securityGroups(List.of(securityGroup))
                        .build()
        );

        var bucket = new Bucket(this, "bucket", BucketProps.builder()
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .encryption(BucketEncryption.S3_MANAGED)
                .removalPolicy(RemovalPolicy.DESTROY)
                .versioned(false)
                .build()
        );


        var executionRole = Role.fromRoleArn(this, "executionRole", "arn:aws:iam::808354942258" +
                ":role/ecsTaskExecutionRole");
        var taskRole = new Role(this, "taskRole", RoleProps.builder()
                .roleName("FactorioEcs-" + serverName)
                .managedPolicies(List.of(
                        ManagedPolicy.fromManagedPolicyName(this, "FactorioRoute53",
                                "FactorioRoute53"),
                        ManagedPolicy.fromManagedPolicyName(this, "FactorioCloudwatch",
                                "FactorioCloudwatch"),
                        ManagedPolicy.fromAwsManagedPolicyName("service-role" +
                                "/AmazonECSTaskExecutionRolePolicy")
                ))
                .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com"))
                .build()
        );

        bucket.grantReadWrite(taskRole);

        var taskDefinition = new FargateTaskDefinition(this, "taskDefinition",
                FargateTaskDefinitionProps.builder()
                        .cpu(512)
                        .memoryLimitMiB(1024)
                        .executionRole(executionRole)
                        .taskRole(taskRole)
                        .build()
        );

        var ecrRepo = Repository.fromRepositoryName(this, "repository", "factorio");

        var containerImage = ContainerImage.fromEcrRepository(ecrRepo, version);

        var container = taskDefinition.addContainer("container",
                ContainerDefinitionOptions.builder()
                        .cpu(512)
                        .environment(Map.of(
                                "S3_BUCKET", bucket.getBucketName(),
                                "HOSTED_ZONE", hostedZone.getHostedZoneId(),
                                "DOMAIN", subDomain + "." + domainName,
                                "SERVER_NAME", serverName
                        ))
                        .essential(true)
                        .healthCheck(HealthCheck.builder()
                                .command(List.of("CMD-SHELL", "cat /opt/factorio/server.pid"))
                                .build()
                        )
                        .dockerLabels(Map.of(
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

        var service = new FargateService(this, "service", FargateServiceProps.builder()
                .assignPublicIp(true)
                .cluster(cluster)
                .desiredCount(1)
                .minHealthyPercent(100)
                .maxHealthyPercent(200)
                .serviceName("factorio-" + serverName)
                .securityGroup(securityGroup)
                .vpcSubnets(SubnetSelection.builder().onePerAz(true).build())
                .deploymentController(DeploymentController.builder().type(DeploymentControllerType.ECS).build())
                .platformVersion(FargatePlatformVersion.LATEST)
                .taskDefinition(taskDefinition)
                .build()
        );

        var scalableTaskCount = service.autoScaleTaskCount(EnableScalingProps.builder()
                .minCapacity(0)
                .maxCapacity(1)
                .build()
        );

        var playersOnline = new Metric(MetricProps.builder()
                .account(props.getEnv().getAccount())
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
