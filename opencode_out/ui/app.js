const API_URL = '/chat';
let sending = false;
let selectedModel = 'minimax-m2.5-free';

const chat          = document.getElementById('chat');
const input         = document.getElementById('input');
const sendBtn       = document.getElementById('send');
const clearBtn      = document.getElementById('clear');
const modelBtn      = document.getElementById('model-btn');
const modelLabel    = document.getElementById('model-label');
const modelDropdown = document.getElementById('model-dropdown');

// ── Model selector ────────────────────────────────────────────────────

modelBtn.onclick = (e) => {
    e.stopPropagation();
    modelDropdown.classList.toggle('hidden');
};

document.addEventListener('click', () => modelDropdown.classList.add('hidden'));

modelDropdown.querySelectorAll('.model-option').forEach(btn => {
    btn.onclick = (e) => {
        e.stopPropagation();
        selectedModel = btn.dataset.model;
        modelLabel.textContent = btn.dataset.label;
        modelDropdown.querySelectorAll('.model-option').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        modelDropdown.classList.add('hidden');
    };
});

// ── Markdown parser — code blocks extracted first, no formatting inside ──

function escHtml(s) {
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}

function buildCodeBlock(lang, code) {
    const displayLang = lang || 'code';
    const safeCode = escHtml(code.trimEnd());
    return `<div class="code-block">` +
        `<div class="code-block-header">` +
        `<span class="code-lang">${escHtml(displayLang)}</span>` +
        `<button class="copy-btn" onclick="copyCode(this)">copy</button>` +
        `</div>` +
        `<pre><code class="lang-${escHtml(lang)}">${safeCode}</code></pre>` +
        `</div>`;
}

function parseMarkdown(text) {
    if (!text) return '';

    const segments = [];
    const fence = /```(\w*)\n?([\s\S]*?)```/g;
    let last = 0;
    let m;

    while ((m = fence.exec(text)) !== null) {
        if (m.index > last) {
            segments.push({ type: 'text', content: text.slice(last, m.index) });
        }
        segments.push({ type: 'code', lang: m[1] || '', content: m[2] });
        last = m.index + m[0].length;
    }
    if (last < text.length) {
        segments.push({ type: 'text', content: text.slice(last) });
    }

    return segments.map(seg => {
        if (seg.type === 'code') {
            return buildCodeBlock(seg.lang, seg.content);
        }

        let s = escHtml(seg.content);
        s = s.replace(/`([^`\n]+)`/g, '<code>$1</code>');
        s = s.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>');
        s = s.replace(/__([^_\n]+)__/g,     '<strong>$1</strong>');
        s = s.replace(/\*([^*\n]+)\*/g,     '<em>$1</em>');
        s = s.replace(/_([^_\n]+)_/g,       '<em>$1</em>');
        s = s.replace(/\[([^\]]+)\]\(([^)]+)\)/g,
            '<a href="$2" target="_blank" rel="noopener">$1</a>');
        s = s.replace(/^### (.+)$/gm, '<h3>$1</h3>');
        s = s.replace(/^## (.+)$/gm,  '<h2>$1</h2>');
        s = s.replace(/^# (.+)$/gm,   '<h1>$1</h1>');
        s = s.replace(/^[\*\-] (.+)$/gm, '<li>$1</li>');
        s = s.replace(/(<li>.*<\/li>\n?)+/g, mm => `<ul>${mm}</ul>`);
        s = s.replace(/^---$/gm, '<hr>');

        const blocks = s.split(/\n\n+/);
        return blocks.map(b => {
            b = b.trim();
            if (!b) return '';
            if (/^<(div|ul|ol|h[1-6]|hr|blockquote)/.test(b)) return b;
            return `<p>${b.replace(/\n/g, '<br>')}</p>`;
        }).join('\n');
    }).join('');
}

window.copyCode = function(btn) {
    const code = btn.closest('.code-block').querySelector('code');
    navigator.clipboard.writeText(code.textContent).then(() => {
        btn.textContent = 'copied!';
        btn.classList.add('copied');
        setTimeout(() => {
            btn.textContent = 'copy';
            btn.classList.remove('copied');
        }, 1800);
    });
};

// ── DOM helpers ───────────────────────────────────────────────────────

function scrollBottom() {
    chat.scrollTop = chat.scrollHeight;
}

function addUserMsg(content) {
    const div = document.createElement('div');
    div.className = 'msg user';
    const inner = document.createElement('div');
    inner.className = 'user-inner';
    inner.textContent = content;
    div.appendChild(inner);
    chat.appendChild(div);
    scrollBottom();
}

function createAssistantShell() {
    const div = document.createElement('div');
    div.className = 'msg assistant streaming';
    div.innerHTML = `<span class="msg-prefix">assistant</span><span class="cursor"></span>`;
    chat.appendChild(div);
    scrollBottom();
    return div;
}

function sealAssistant(div, text) {
    div.classList.remove('streaming');
    div.innerHTML = `<span class="msg-prefix">assistant</span>` + parseMarkdown(text);
}

function createThinkingBlock() {
    const wrapper = document.createElement('div');
    wrapper.className = 'thinking-wrapper';
    wrapper.innerHTML = `
        <button class="thinking-header">
            <svg width="11" height="11" viewBox="0 0 24 24" fill="none"
                 stroke="currentColor" stroke-width="2" stroke-linecap="round">
                <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/>
                <circle cx="12" cy="12" r="10"/>
                <line x1="12" y1="17" x2="12.01" y2="17"/>
            </svg>
            <span class="thinking-label">thinking…</span>
            <svg class="thinking-chevron" width="9" height="9" viewBox="0 0 24 24" fill="none"
                 stroke="currentColor" stroke-width="2.5">
                <polyline points="6 9 12 15 18 9"/>
            </svg>
        </button>
        <div class="thinking-body open"></div>`;

    chat.appendChild(wrapper);
    scrollBottom();

    const header = wrapper.querySelector('.thinking-header');
    const body   = wrapper.querySelector('.thinking-body');
    let open = true;

    header.onclick = () => {
        open = !open;
        body.classList.toggle('open', open);
        wrapper.classList.toggle('collapsed', !open);
    };

    return { wrapper, body, header };
}

function sealThinking(block) {
    block.header.querySelector('.thinking-label').textContent = 'thought process';
}

function createToolPill(name, args) {
    const div = document.createElement('div');
    div.className = 'tool-pill';

    let icon, label;
    if (name === 'web_search') {
        icon = `<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" style="flex-shrink:0"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>`;
        label = `searching&nbsp;<em>${escHtml(args.query || '')}</em>`;
    } else {
        icon = `<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>`;
        label = `fetching&nbsp;<em>${escHtml((args.url || '').replace(/^https?:\/\//, '').slice(0, 55))}</em>`;
    }

    div.innerHTML = `<span class="tool-spinner"></span>${icon}<span>${label}</span>`;
    chat.appendChild(div);
    scrollBottom();
    return div;
}

function setLoading(on) {
    sendBtn.disabled  = on;
    clearBtn.disabled = on;
    sendBtn.innerHTML = on
        ? `<span class="dots"><span></span><span></span><span></span></span>`
        : `<svg width="13" height="13" viewBox="0 0 24 24" fill="none"
               stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
               <line x1="22" y1="2" x2="11" y2="13"/>
               <polygon points="22 2 15 22 11 13 2 9 22 2"/>
           </svg>`;
}

// ── Send ──────────────────────────────────────────────────────────────

async function send() {
    if (sending) return;
    const userMsg = input.value.trim();
    if (!userMsg) return;

    input.value = '';
    input.style.height = 'auto';
    addUserMsg(userMsg);

    sending = true;
    setLoading(true);

    // Per-turn state — each is a distinct DOM node, never shared
    let thinkingBlock = null;   // current open thinking block
    let assistantDiv  = null;   // current streaming assistant bubble
    let toolPill      = null;   // current tool pill
    let assistantText = '';

    try {
        const resp = await fetch(API_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message: userMsg, model: selectedModel })
        });
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);

        const reader  = resp.body.getReader();
        const decoder = new TextDecoder();
        let buf = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buf += decoder.decode(value, { stream: true });

            const lines = buf.split('\n');
            buf = lines.pop();

            for (const line of lines) {
                if (!line.startsWith('data: ')) continue;
                const raw = line.slice(6).trim();
                if (raw === '[DONE]') continue;
                let ev;
                try { ev = JSON.parse(raw); } catch { continue; }

                switch (ev.type) {

                    case 'thinking': {
                        // Thinking always comes before text/tool — create once per burst
                        if (!thinkingBlock) {
                            thinkingBlock = createThinkingBlock();
                        }
                        thinkingBlock.body.textContent += ev.text;
                        scrollBottom();
                        break;
                    }

                    case 'text': {
                        // Seal thinking block when text starts arriving
                        if (thinkingBlock) {
                            sealThinking(thinkingBlock);
                            thinkingBlock = null;
                        }
                        // Seal completed tool pill
                        if (toolPill) {
                            toolPill.classList.add('done');
                            toolPill = null;
                        }
                        // Open new assistant shell if needed
                        if (!assistantDiv) {
                            assistantText = '';
                            assistantDiv  = createAssistantShell();
                        }
                        assistantText += ev.text;
                        assistantDiv.innerHTML = `<span class="msg-prefix">assistant</span>`
                            + parseMarkdown(assistantText)
                            + '<span class="cursor"></span>';
                        scrollBottom();
                        break;
                    }

                    case 'tool_use': {
                        // Seal everything open before showing tool pill
                        if (thinkingBlock) {
                            sealThinking(thinkingBlock);
                            thinkingBlock = null;
                        }
                        if (assistantDiv) {
                            sealAssistant(assistantDiv, assistantText);
                            assistantDiv  = null;
                            assistantText = '';
                        }
                        if (toolPill) {
                            toolPill.classList.add('done');
                        }
                        toolPill = createToolPill(ev.name, ev.args);
                        break;
                    }

                    case 'tool_done': {
                        if (toolPill) {
                            const spinner = toolPill.querySelector('.tool-spinner');
                            if (spinner) spinner.outerHTML = `<span class="tool-check">✓</span>`;
                        }
                        break;
                    }

                    case 'error': {
                        if (thinkingBlock) { sealThinking(thinkingBlock); thinkingBlock = null; }
                        if (!assistantDiv) { assistantText = ''; assistantDiv = createAssistantShell(); }
                        assistantDiv.classList.remove('streaming');
                        assistantDiv.innerHTML = `<span class="msg-prefix">assistant</span><span class="error-msg">⚠ ${escHtml(ev.text)}</span>`;
                        assistantDiv = null;
                        break;
                    }

                    case 'done': {
                        if (thinkingBlock) { sealThinking(thinkingBlock); thinkingBlock = null; }
                        if (assistantDiv)  { sealAssistant(assistantDiv, assistantText); assistantDiv = null; }
                        if (toolPill)      { toolPill.classList.add('done'); toolPill = null; }
                        break;
                    }
                }
            }
        }

        // Safety net
        if (thinkingBlock) sealThinking(thinkingBlock);
        if (assistantDiv)  sealAssistant(assistantDiv, assistantText);
        if (toolPill)      toolPill.classList.add('done');

    } catch (e) {
        const d = assistantDiv || createAssistantShell();
        d.classList.remove('streaming');
        d.innerHTML = `<span class="msg-prefix">assistant</span><span class="error-msg">⚠ ${escHtml(e.message)}</span>`;
    }

    sending = false;
    setLoading(false);
    input.focus();
}

// ── Controls ──────────────────────────────────────────────────────────

clearBtn.onclick = async () => {
    try { await fetch('/clear', { method: 'POST' }); } catch {}
    chat.innerHTML = '';
};

sendBtn.onclick = send;

input.onkeydown = e => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
};

input.oninput = () => {
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 120) + 'px';
};

setLoading(false);
input.focus();
