const fs = require('fs');
const html = fs.readFileSync('rank2.html', 'utf16le') || fs.readFileSync('rank2.html', 'utf8');

const m1 = html.match(/<script id="__NEXT_DATA__"/);
const m2 = html.match(/__next_f\.push/);

console.log('NEXT_DATA:', !!m1, '__next_f:', !!m2);

if (m2) {
    const matches = [...html.matchAll(/self\.__next_f\.push\(\[(.*?)\]\)/g)];
    for (let i = 0; i < matches.length; i++) {
        const payload = matches[i][1];
        if (payload.includes('comics')) {
            console.log(`Match ${i} contains 'comics', length: ${payload.length}`);
            fs.writeFileSync(`payload_${i}.json`, `[${payload}]`, 'utf8');
        }
    }
}
