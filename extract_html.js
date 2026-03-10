const fs = require('fs');
const html = fs.readFileSync('rank2.html', 'utf16le');

// Use regex to locate a href="/comics/xxx"
const regex = /<a[^>]*href="(\/comics\/\d+)"[^>]*>([\s\S]*?)<\/a>/g;
let match;
let count = 0;
while ((match = regex.exec(html)) !== null && count < 5) {
    const url = match[1];
    const innerHtml = match[2];
    
    // Find title (text without tags or alt text)
    const titleMatch = innerHtml.match(/alt="([^"]+)"/);
    const title = titleMatch ? titleMatch[1] : innerHtml.replace(/<[^>]+>/g, '').trim();
    
    // Find image src
    const imgMatch = innerHtml.match(/<img[^>]*src="([^"]+)"/);
    const imgSrc = imgMatch ? imgMatch[1] : null;
    
    console.log(`URL: ${url}`);
    console.log(`Title: ${title.replace(/\s+/g, ' ').substring(0, 50)}`);
    console.log(`Image: ${imgSrc}`);
    console.log('---');
    count++;
}
