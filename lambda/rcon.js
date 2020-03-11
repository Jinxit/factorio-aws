const AWS = require('aws-sdk');
const secretsManager = new AWS.SecretsManager();

const Rcon = require('/opt/node_modules/rcon');

exports.main = async function(event, context) {
    try {
        const password = await new Promise((resolve, reject) => {
            secretsManager.getSecretValue({
                SecretId: process.env.SECRET_NAME
            }, function(err, data) {
                if (err) reject(err);
                else     resolve(data);
            });
        });
        const client = await new Promise((resolve, reject) => {
            const client = new Rcon(event.pathParameters.serverName + '.factorio.' + process.env.DOMAIN_NAME, 27015, password.SecretString, {
                tcp: true,
                challenge: false
            });
            client.on('auth', () => {
                resolve(client);
            });
            client.connect();
        });

        const response = await new Promise((resolve, reject) => {
            client.on('response', str => {
                resolve(str);
            });
            client.on('error', err => {
                reject(err);
            });
            client.send(JSON.parse(event.body).command);
        });

        return {
            statusCode: 200,
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({response})
        };
    } catch(error) {
        const body = error.stack || JSON.stringify(error, null, 2);
        return {
            statusCode: 500,
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify(body)
        }
    }
};