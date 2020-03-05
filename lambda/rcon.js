const AWS = require('aws-sdk');
const rcon = require('rcon');

const domainName = process.env.DOMAIN_NAME;

exports.main = async function(event, context) {
    try {
        var matches = /^\/([^\/]+?)\/?$/g.exec(event.path);

        var response = await new Promise(((resolve, reject) => {
            var client = new Rcon(event.serverName + '.' + domainName, 27015, password, {
                tcp: true,
                challenge: false
            });
            client.on('response', str => resolve(str));
            client.on('error', err => reject(err));
            client.connect();
            client.send(event.command);
        }));

        return {
            statusCode: 200,
            headers: {"Content-Type": "text/plain"},
            body: response
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