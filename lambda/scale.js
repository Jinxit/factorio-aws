const AWS = require('aws-sdk');
const ecs = new AWS.ECS();

const domainName = process.env.CLUSTER;

exports.main = async function(event, context) {
    try {
        var response = await new Promise((resolve, reject) => {
            ecs.updateService({
                service: event.pathParameters.service,
                cluster: process.env.CLUSTER,
                desiredCount: 1,
                forceNewDeployment: false
            }, function(err, data) {
                if (err) reject(err);
                else     resolve(data);
            });
        });

        return {
            statusCode: 200,
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify(response)
        };
    } catch(error) {
        var body = error.stack || JSON.stringify(error, null, 2);
        return {
            statusCode: 500,
            headers: {},
            body: JSON.stringify(body)
        }
    }
};