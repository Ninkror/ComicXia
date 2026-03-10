const fs = require('fs');
const html = fs.readFileSync('rank3.html', 'utf8');

// Try __NEXT_DATA__
const nextDataMatch = html.match(/<script id="__NEXT_DATA__" type="application\/json">(.*?)<\/script>/);
if (nextDataMatch) {
    console.log("__NEXT_DATA__ found");
    const data = JSON.parse(nextDataMatch[1]);
    console.log(JSON.stringify(data.props, null, 2).substring(0, 500));
} else {
    console.log("__NEXT_DATA__ not found");
}

// Try Next.js App Router script payload
const appRouterMatches = html.matchAll(/self\.__next_f\.push\((.*)\)/g);
let foundAppRouter = false;
for (const match of appRouterMatches) {
    foundAppRouter = true;
    console.log("AppRouter match:", match[1].substring(0, 100));
}
if (!foundAppRouter) {
    console.log("AppRouter not found.");
}
