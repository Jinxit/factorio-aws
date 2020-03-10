const AWS = require('aws-sdk');
const codePipeline = new AWS.CodePipeline();

exports.main = async function(event, context) {
    try {
        var response = await new Promise((resolve, reject) => {
            codePipeline.startPipelineExecution({
                name: process.env.PIPELINE
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
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify(body)
        }
    }
};