const AWS = require('aws-sdk');
const rcon = require('rcon');

exports.main = async function(event, context) {
    try {
        const response = await new Promise((resolve, reject) => {
            const client = new Rcon(event.serverName + '.' + process.env.DOMAIN_NAME, 27015, password, {
                tcp: true,
                challenge: false
            });
            client.on('response', str => resolve(str));
            client.on('error', err => reject(err));
            client.connect();
            client.send(event.command);
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
            headers: {},
            body: JSON.stringify(body)
        }
    }
};