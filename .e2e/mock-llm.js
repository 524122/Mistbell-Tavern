// E2E mock: OpenAI 兼容 /v1/chat/completions（SSE 流式 + 非流式）
// v2：首个 chunk 发 thinking（reasoning_content），随后发 content chunks；
// MOCK_CHUNK_MS 控制节奏（默认 120ms）；MOCK_THINK_HOLD 可在 thinking 后停顿（模拟取消停在 think 段内）。
// 仅用于模拟器端到端实测，不属于应用源码。
const http = require('http');

const PORT = 18765;
const CHUNK_MS = process.env.MOCK_CHUNK_MS ? parseInt(process.env.MOCK_CHUNK_MS) : 120;
const THINK_HOLD_MS = process.env.MOCK_THINK_HOLD ? parseInt(process.env.MOCK_THINK_HOLD) : 0;
let reqCount = 0;
const ts = () => new Date().toISOString();

const server = http.createServer((req, res) => {
  if (req.method === 'POST' && (req.url === '/v1/chat/completions' || req.url === '/chat/completions')) {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      reqCount++;
      let model = 'mock-model';
      let stream = true;
      let msgCount = -1;
      let lastUser = '';
      let roles = '';
      let lastMsg = '';
      try {
        const j = JSON.parse(body);
        model = j.model || model;
        stream = j.stream !== false;
        msgCount = (j.messages || []).length;
        roles = (j.messages || []).map((m) => m.role).join(',');
        const last = (j.messages || []).slice(-1).pop();
        lastMsg = last ? String(last.content).slice(0, 60) : '';
        const lu = (j.messages || []).filter((m) => m.role === 'user').pop();
        lastUser = lu ? String(lu.content).slice(0, 80) : '';
      } catch (e) { /* ignore */ }
      console.log(`[${ts()}] REQ#${reqCount} url=${req.url} stream=${stream} model=${model} messages=${msgCount} roles=[${roles}] lastUser="${lastUser}" lastMsg="${lastMsg}"`);

      // 步骤序列：先 thinking 一段，再 content 若干段（长文本：让回复气泡足够高，露出面板下缘供长按）
      const filler = '这是长回复段落，用于端到端实测中让气泡高度超过面板下缘，包含足够多的字符来占据多行显示区域。';
      const steps = [
        { kind: 'think', text: '这是慢速mock的思维链，取消时若已闭合应进 thinking 字段。' },
        { kind: 'content', text: '你好，这是长回复的开头。' },
        { kind: 'content', text: filler },
        { kind: 'content', text: filler },
        { kind: 'content', text: filler },
        { kind: 'content', text: '以上是长回复的结尾。' },
      ];
      if (stream) {
        res.writeHead(200, {
          'Content-Type': 'text/event-stream',
          'Cache-Control': 'no-cache',
          Connection: 'keep-alive',
        });
        const id = 'cmpl-mock-' + Date.now();
        let i = 0;
        const send = (obj) => res.write('data: ' + JSON.stringify(obj) + '\n\n');
        const chunkOf = (s) => ({
          id,
          object: 'chat.completion.chunk',
          created: Math.floor(Date.now() / 1000),
          model,
          choices: [{
            index: 0,
            delta: s.kind === 'think' ? { reasoning_content: s.text } : { content: s.text },
            finish_reason: null,
          }],
        });
        const timer = setInterval(() => {
          if (i < steps.length) {
            // thinking（立即首步）已发、下一内容步未发：长停顿模拟"取消停在 think 段内"
            if (i === 1 && THINK_HOLD_MS > 0) {
              clearInterval(timer);
              setTimeout(() => {
                const t2 = setInterval(() => {
                  if (i < steps.length) { send(chunkOf(steps[i])); i++; }
                  else { finish(); clearInterval(t2); }
                }, CHUNK_MS);
              }, THINK_HOLD_MS);
              return;
            }
            send(chunkOf(steps[i]));
            i++;
          } else {
            finish();
            clearInterval(timer);
          }
        }, CHUNK_MS);
        // 首步立即发送（setInterval 首跳要等 CHUNK_MS，会让首个 chunk 与停止竞态）
        if (steps.length > 0) { send(chunkOf(steps[0])); i = 1; }
        const finish = () => {
          const fin = {
            id, object: 'chat.completion.chunk', created: Math.floor(Date.now() / 1000), model,
            choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
            usage: { prompt_tokens: 42, completion_tokens: 30, total_tokens: 72 },
          };
          send(fin);
          res.write('data: [DONE]\n\n');
          res.end();
          console.log(`[${ts()}] DONE REQ#${reqCount} (${steps.length} steps sent)`);
        };
        res.on('close', () => { clearInterval(timer); console.log(`[${ts()}] CLIENT CLOSED REQ#${reqCount} after ${i}/${steps.length} steps`); });
      } else {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
          id: 'cmpl-mock-' + Date.now(), object: 'chat.completion', created: Math.floor(Date.now() / 1000), model,
          choices: [{ index: 0, message: { role: 'assistant', content: '你好，这是来自mock服务的流式回复。' }, finish_reason: 'stop' }],
          usage: { prompt_tokens: 42, completion_tokens: 20, total_tokens: 62 },
        }));
        console.log(`[${ts()}] DONE REQ#${reqCount} (non-stream)`);
      }
    });
    return;
  }
  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ error: { message: 'not found: ' + req.url } }));
});

server.listen(PORT, '127.0.0.1', () => console.log(`[${ts()}] mock LLM listening on 127.0.0.1:${PORT} chunkMs=${CHUNK_MS} thinkHold=${THINK_HOLD_MS}`));
