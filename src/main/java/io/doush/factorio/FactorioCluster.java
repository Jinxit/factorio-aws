package io.doush.factorio;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;
import org.jetbrains.annotations.NotNull;
import software.amazon.awscdk.core.*;
import software.amazon.awscdk.services.apigateway.LambdaIntegration;
import software.amazon.awscdk.services.apigateway.MethodOptions;
import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.codebuild.*;
import software.amazon.awscdk.services.codepipeline.Artifact;
import software.amazon.awscdk.services.codepipeline.Pipeline;
import software.amazon.awscdk.services.codepipeline.StageOptions;
import software.amazon.awscdk.services.codepipeline.actions.CodeBuildAction;
import software.amazon.awscdk.services.codepipeline.actions.CodeBuildActionType;
import software.amazon.awscdk.services.codepipeline.actions.GitHubSourceAction;
import software.amazon.awscdk.services.codepipeline.actions.GitHubTrigger;
import software.amazon.awscdk.services.dynamodb.*;
import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ecr.LifecycleRule;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecr.TagStatus;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.LayerVersion;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.route53.HostedZone;
import software.amazon.awscdk.services.route53.HostedZoneProviderProps;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.assets.AssetOptions;

import java.util.*;
import java.util.stream.Collectors;

public class FactorioCluster extends Construct {
    public FactorioCluster(@NotNull Construct scope, @NotNull String id, String domainName,
                           IVpc vpc, String region, String account) {
        super(scope, id);

        var tableName = this.getNode().getPath().replaceAll("/", "-");
        var dynamoTable = Table.Builder.create(this, "table")
                .tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .partitionKey(Attribute.builder().name("serverName").type(AttributeType.STRING).build())
                .removalPolicy(RemovalPolicy.DESTROY)
                .serverSideEncryption(true)
                .stream(StreamViewType.NEW_IMAGE)
                .build();

        var cluster = Cluster.Builder.create(this, "cluster")
                .vpc(vpc)
                .containerInsights(true)
                .build();

        String domainNameUnderlined = domainName.replaceAll("\\.", "_");
        var api = RestApi.Builder.create(this, "restApi")
                .restApiName("factorio_" + domainNameUnderlined)
                .build();

        var commonLayer = LayerVersion.Builder.create(this, "common-layer")
                .compatibleRuntimes(List.of(Runtime.NODEJS_12_X))
                .code(Code.fromAsset("lambda"))
                .build();

        var lambdaRcon = Function.Builder.create(this, "lambdaRcon")
                .runtime(Runtime.NODEJS_12_X)
                .layers(List.of(commonLayer))
                .code(Code.fromAsset("lambda", AssetOptions.builder()
                        .exclude(List.of("node_modules"))
                        .build())
                )
                .handler("rcon.main")
                .environment(Collections.singletonMap(
                        "DOMAIN_NAME", domainName
                ))
                .build();

        api.getRoot().addResource("rcon").addResource("{serverName}").addMethod("POST",
                LambdaIntegration.Builder.create(lambdaRcon)
                        .build(),
                MethodOptions.builder()
                        .apiKeyRequired(true)
                        .build()
        );

        //var lambdaScaleRole = Role.Builder.create(this, "lambdaScaleRole")
        //        .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
        //        .build();

        var lambdaScale = Function.Builder.create(this, "lambdaScale")
                .runtime(Runtime.NODEJS_12_X)
                .layers(List.of(commonLayer))
                .code(Code.fromAsset("lambda", AssetOptions.builder()
                        .exclude(List.of("node_modules"))
                        .build())
                )
                .handler("scale.main")
                .environment(Collections.singletonMap("CLUSTER", cluster.getClusterName()))
                //.role(lambdaScaleRole)
                .build();

        api.getRoot().addResource("scale").addResource("{service}").addMethod("PUT",
                LambdaIntegration.Builder.create(lambdaScale)
                        .build(),
                MethodOptions.builder()
                        .apiKeyRequired(true)
                        .build()
        );

        var hostedZone = HostedZone.fromLookup(this, "hostedZone",
                HostedZoneProviderProps.builder()
                        .domainName(domainName)
                        .build()
        );

        var ecrRepo = Repository.Builder.create(this, "repository")
                .removalPolicy(RemovalPolicy.DESTROY)
                .lifecycleRules(List.of(
                        LifecycleRule.builder()
                                .tagStatus(TagStatus.UNTAGGED)
                                .maxImageAge(Duration.days(1))
                                .build()
                ))
                .build();

        ServicePrincipal ecsTasksPrincipal = new ServicePrincipal("ecs-tasks.amazonaws.com");
        var executionRole = Role.Builder.create(this, "executionRole")
                .managedPolicies(List.of(ManagedPolicy
                        .fromAwsManagedPolicyName("service-role/AmazonECSTaskExecutionRolePolicy")))
                .assumedBy(ecsTasksPrincipal)
                .build();

        ecrRepo.grantPull(executionRole);

        var taskRole = Role.Builder.create(this, "taskRole")
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role" +
                                "/AmazonECSTaskExecutionRolePolicy")
                ))
                .inlinePolicies(new TreeMap<>() {{
                    put("FactorioRoute53", PolicyDocument.Builder.create()
                            .statements(List.of(
                                    PolicyStatement.Builder.create()
                                            .actions(List.of("route53:ChangeResourceRecordSets"))
                                            .resources(List.of(hostedZone.getHostedZoneArn()))
                                            .build()
                            ))
                            .build());
                    put("FactorioCloudwatch", PolicyDocument.Builder.create()
                            .statements(List.of(
                                    PolicyStatement.Builder.create()
                                            .actions(List.of("cloudwatch:PutMetricData"))
                                            .resources(List.of("*"))
                                            .build()
                            ))
                            .build());
                }})
                .assumedBy(ecsTasksPrincipal)
                .build();

        var securityGroup = SecurityGroup.Builder.create(this, "securityGroup")
                .vpc(vpc)
                .build();
        securityGroup.addIngressRule(Peer.anyIpv4(), Port.udp(34197));
        securityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(27015));
        securityGroup.addEgressRule(Peer.anyIpv4(), Port.allTraffic());

        List<Map<String, AttributeValue>> items = new ArrayList<>();
        try {
            var dynamoClient = AmazonDynamoDBClientBuilder.standard()
                    .withRegion(System.getenv("CDK_DEFAULT_REGION"))
                    .build();
            items = dynamoClient.scan(tableName, List.of(
                    "serverName",
                    "version"
            )).getItems();
        } catch (ResourceNotFoundException ex) {
            //System.err.println("Table " + tableName + " does not exist (yet)");
            //ex.printStackTrace(System.err);
        } catch (Exception ex) {
            //System.err.println("Failed to fetch from DynamoDB");
            //ex.printStackTrace(System.err);
        }

        for (var item : items) {
            var serverName = item.get("serverName").getS();
            var version = item.get("version").getS();
            var server = new FactorioServer(this, "factorio-server-" + serverName, serverName,
                    domainName, version, hostedZone, securityGroup,
                    cluster, executionRole, taskRole, ecrRepo
            );
            lambdaScale.addToRolePolicy(PolicyStatement.Builder.create()
                    .resources(List.of("arn:aws:ecs:" + region + ":" + account + ":service/" +
                            cluster.getClusterName() + "/" + server.service.getServiceName()))
                    .actions(List.of("ecs:UpdateService"))
                    .build());
        }

        var codeBuildDocker = PipelineProject.Builder.create(this, "dockerCodeBuild")
                .environment(BuildEnvironment.builder()
                        .computeType(ComputeType.SMALL)
                        .buildImage(LinuxBuildImage.STANDARD_3_0)
                        .environmentVariables(Collections.singletonMap(
                                "FACTORIO_VERSION", BuildEnvironmentVariable.builder()
                                        .type(BuildEnvironmentVariableType.PLAINTEXT)
                                        .value("")
                                        .build()
                        ))
                        .privileged(true)
                        .build())
                .vpc(vpc)
                .build();

        ecrRepo.grantPullPush(codeBuildDocker.getGrantPrincipal());

        var codeBuildPrincipal = new ServicePrincipal("codebuild.amazonaws.com");
        var cdkRole = Role.Builder.create(this, "cdkRole").assumedBy(codeBuildPrincipal).build();

        var codeBuildCdk = PipelineProject.Builder.create(this, "cdkCodeBuild")
                .environment(BuildEnvironment.builder()
                        .computeType(ComputeType.SMALL)
                        .buildImage(LinuxBuildImage.STANDARD_3_0)
                        .environmentVariables(Collections.singletonMap(
                                "DOMAIN_NAME", BuildEnvironmentVariable.builder()
                                        .type(BuildEnvironmentVariableType.PLAINTEXT)
                                        .value(domainName)
                                        .build()
                        ))
                        .build())
                .role(cdkRole)
                .vpc(vpc)
                .build();

        cdkRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of("*"))
                .resources(List.of("*"))
                .build()
        );

        dynamoTable.grantReadData(cdkRole);
        cdkRole.addManagedPolicy(
                ManagedPolicy.fromAwsManagedPolicyName("IAMFullAccess")
        );
        cdkRole.addManagedPolicy(
                ManagedPolicy.fromAwsManagedPolicyName("AmazonS3FullAccess")
        );
        cdkRole.addManagedPolicy(
                ManagedPolicy.fromAwsManagedPolicyName("AmazonVPCFullAccess")
        );
        cdkRole.addManagedPolicy(
                ManagedPolicy.fromAwsManagedPolicyName("AWSCodeDeployRoleForECS")
        );
        cdkRole.addManagedPolicy(
                ManagedPolicy.fromAwsManagedPolicyName("AmazonECS_FullAccess")
        );
        cdkRole.addManagedPolicy(
                ManagedPolicy.fromAwsManagedPolicyName("AWSCloudFormationFullAccess")
        );
        cdkRole.addManagedPolicy(
                ManagedPolicy.fromAwsManagedPolicyName("CloudWatchFullAccess")
        );

        var oauthToken = SecretValue.secretsManager("FactorioCredentials",
                SecretsManagerSecretOptions.builder()
                        .jsonField("GitHub")
                        .build());

        var artifactBucket = Bucket.Builder.create(this, "artifactBucket")
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        var codePipeline = Pipeline.Builder.create(this, "pipeline")
                .restartExecutionOnUpdate(true)
                .artifactBucket(artifactBucket)
                .build();

        codePipeline.getRole().grant(codeBuildCdk.getGrantPrincipal(), "*");
        codePipeline.getRole().grant(codeBuildDocker.getGrantPrincipal(), "*");

        codePipeline.addStage(StageOptions.builder()
                .stageName("Source")
                .actions(List.of(
                        GitHubSourceAction.Builder.create()
                                .actionName("Docker-Image")
                                .owner("Jinxit")
                                .repo("factorio-docker")
                                .trigger(GitHubTrigger.WEBHOOK)
                                .branch("master")
                                .variablesNamespace("factorio-docker-ns")
                                .output(Artifact.artifact("factorio-docker"))
                                .oauthToken(oauthToken)
                                .build(),
                        GitHubSourceAction.Builder.create()
                                .actionName("Install-Script")
                                .owner("Jinxit")
                                .repo("factorio-init")
                                .trigger(GitHubTrigger.WEBHOOK)
                                .branch("master")
                                .variablesNamespace("factorio-init-ns")
                                .output(Artifact.artifact("factorio-init"))
                                .oauthToken(oauthToken)
                                .build(),
                        GitHubSourceAction.Builder.create()
                                .actionName("CDK-Infrastructure")
                                .owner("Jinxit")
                                .repo("factorio-aws")
                                .trigger(GitHubTrigger.WEBHOOK)
                                .branch("master")
                                .variablesNamespace("factorio-aws-ns")
                                .output(Artifact.artifact("factorio-aws"))
                                .oauthToken(oauthToken)
                                .build()
                ))
                .build()
        );

        if (items.size() > 0) {
            codePipeline.addStage(StageOptions.builder()
                    .stageName("Build")
                    .actions(items.stream().map(server -> CodeBuildAction.Builder.create()
                            .actionName(server.get("version").getS())
                            .project(codeBuildDocker)
                            .input(Artifact.artifact("factorio-docker"))
                            .type(CodeBuildActionType.BUILD)
                            .variablesNamespace("factorio-" + server.get("version")
                                    .getS().replaceAll("\\.", "_"))
                            .environmentVariables(new TreeMap<>() {{
                                put("FACTORIO_VERSION",
                                        BuildEnvironmentVariable.builder()
                                                .type(BuildEnvironmentVariableType.PLAINTEXT)
                                                .value(server.get("version").getS())
                                                .build());
                                put("IMAGE_REPO_NAME",
                                        BuildEnvironmentVariable.builder()
                                                .type(BuildEnvironmentVariableType.PLAINTEXT)
                                                .value(ecrRepo.getRepositoryName())
                                                .build());
                                put("AWS_ACCOUNT_ID", BuildEnvironmentVariable.builder()
                                        .type(BuildEnvironmentVariableType.PLAINTEXT)
                                        .value(System.getenv("CDK_DEFAULT_ACCOUNT"))
                                        .build());
                            }})
                            .build()
                    ).collect(Collectors.toList()))
                    .build()
            );
        }

        codePipeline.addStage(StageOptions.builder()
                .stageName("Deploy")
                .actions(List.of(
                        CodeBuildAction.Builder.create()
                                .actionName("Deploy")
                                .project(codeBuildCdk)
                                .input(Artifact.artifact("factorio-aws"))
                                .environmentVariables(Collections.singletonMap(
                                        "DOMAIN_NAME", BuildEnvironmentVariable.builder()
                                                .type(BuildEnvironmentVariableType.PLAINTEXT)
                                                .value(domainName)
                                                .build()
                                ))
                                .build()
                ))
                .build()
        );
    }

}
