import os
import json
import re
import requests
from flask import Flask, send_file, request, jsonify, send_from_directory, Response, stream_with_context
from python.config import API_URL, MODEL, HOST, PORT

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
app = Flask(__name__, template_folder=ROOT, static_folder=ROOT + '/ui')

history = []

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "web_search",
            "description": "Search the web for current information, news, and facts.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "The search query"
                    },
                    "num_results": {
                        "type": "integer",
                        "description": "Number of results to return (default 8)",
                        "default": 8
                    }
                },
                "required": ["query"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "web_fetch",
            "description": "Fetch and read the text content of a webpage given its URL.",
            "parameters": {
                "type": "object",
                "properties": {
                    "url": {
                        "type": "string",
                        "description": "The URL to fetch"
                    }
                },
                "required": ["url"]
            }
        }
    }
]

def strip_html(html):
    html = re.sub(r'<script[^>]*>.*?</script>', '', html, flags=re.DOTALL | re.IGNORECASE)
    html = re.sub(r'<style[^>]*>.*?</style>', '', html, flags=re.DOTALL | re.IGNORECASE)
    html = re.sub(r'<!--.*?-->', '', html, flags=re.DOTALL)
    html = re.sub(r'<br\s*/?>', '\n', html, flags=re.IGNORECASE)
    html = re.sub(r'<p[^>]*>', '\n', html, flags=re.IGNORECASE)
    html = re.sub(r'<h[1-6][^>]*>', '\n## ', html, flags=re.IGNORECASE)
    html = re.sub(r'<li[^>]*>', '\n- ', html, flags=re.IGNORECASE)
    html = re.sub(r'<[^>]+>', '', html)
    html = html.replace('&nbsp;', ' ').replace('&amp;', '&')
    html = html.replace('&lt;', '<').replace('&gt;', '>').replace('&quot;', '"')
    html = re.sub(r'\n{3,}', '\n\n', html)
    html = re.sub(r'[ \t]+', ' ', html)
    return html.strip()


def websearch(query, num_results=8):
    try:
        resp = requests.get(
            "https://html.duckduckgo.com/html/",
            params={"q": query},
            headers={"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36"},
            timeout=30
        )
        titles   = re.findall(r'class="result__a"[^>]*>(.*?)</a>', resp.text, re.DOTALL)
        urls     = re.findall(r'class="result__url"[^>]*>(.*?)</span>', resp.text, re.DOTALL)
        snippets = re.findall(r'class="result__snippet"[^>]*>(.*?)</a>', resp.text, re.DOTALL)

        titles   = [re.sub(r'<[^>]+>', '', t).strip() for t in titles]
        urls     = [u.strip() for u in urls]
        snippets = [re.sub(r'<[^>]+>', '', s).strip() for s in snippets]

        results = list(zip(titles, urls, snippets))[:num_results]
        if not results:
            return f"No results found for: {query}"

        lines = [f"Search results for: **{query}**\n"]
        for i, (title, url, snippet) in enumerate(results, 1):
            lines.append(f"{i}. **{title}**")
            if url:  lines.append(f"   {url}")
            if snippet: lines.append(f"   {snippet}")
            lines.append("")
        return "\n".join(lines)
    except Exception as e:
        return f"Search error: {e}"


def webfetch(url):
    try:
        resp = requests.get(
            url,
            headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"},
            timeout=30
        )
        return strip_html(resp.text)[:30000]
    except Exception as e:
        return f"Fetch error: {e}"


def run_tool(name, args):
    if name == "web_search":
        return websearch(args.get("query", ""), args.get("num_results", 8))
    elif name == "web_fetch":
        return webfetch(args.get("url", ""))
    return f"Unknown tool: {name}"


@app.route("/")
def home():
    return send_file(os.path.join(ROOT, "index.html"))

@app.route("/ui/<path:filename>")
def static_files(filename):
    return send_from_directory(ROOT + '/ui', filename)


@app.route("/chat", methods=["POST"])
def chat():
    global history
    data = request.json
    user_msg = data.get("message", "")
    model = data.get("model", MODEL)
    history.append({"role": "user", "content": user_msg})

    def generate():
        global history
        messages     = list(history)
        full_content = ""

        for _round in range(6):
            payload = {
                "model": model,
                "messages": messages,
                "stream": True,
                "tools": TOOLS,
                "tool_choice": "auto"
            }

            try:
                api_resp = requests.post(API_URL, json=payload, stream=True, timeout=180)
                api_resp.raise_for_status()
            except Exception as e:
                yield f"data: {json.dumps({'type': 'error', 'text': str(e)})}\n\n"
                return

            tool_calls_acc = {}
            round_content  = ""

            for raw in api_resp.iter_lines():
                if not raw:
                    continue
                line = raw.decode("utf-8", errors="replace")
                if not line.startswith("data: "):
                    continue
                data_str = line[6:].strip()
                if data_str == "[DONE]":
                    break
                try:
                    chunk = json.loads(data_str)
                except json.JSONDecodeError:
                    continue

                choice = (chunk.get("choices") or [{}])[0]
                delta  = choice.get("delta", {})

                thinking_chunk = delta.get("reasoning") or delta.get("thinking") or ""
                if thinking_chunk:
                    yield f"data: {json.dumps({'type': 'thinking', 'text': thinking_chunk})}\n\n"

                content_chunk = delta.get("content") or ""
                if content_chunk:
                    round_content += content_chunk
                    full_content  += content_chunk
                    yield f"data: {json.dumps({'type': 'text', 'text': content_chunk})}\n\n"

                for tc_delta in delta.get("tool_calls", []):
                    idx = tc_delta.get("index", 0)
                    if idx not in tool_calls_acc:
                        tool_calls_acc[idx] = {"id": "", "name": "", "arguments": ""}
                    if tc_delta.get("id"):
                        tool_calls_acc[idx]["id"] = tc_delta["id"]
                    fn = tc_delta.get("function", {})
                    if fn.get("name"):
                        tool_calls_acc[idx]["name"] += fn["name"]
                    if fn.get("arguments"):
                        tool_calls_acc[idx]["arguments"] += fn["arguments"]

            if not tool_calls_acc:
                break

            tc_list = []
            for idx in sorted(tool_calls_acc.keys()):
                tc = tool_calls_acc[idx]
                tc_list.append({
                    "id": tc["id"] or f"call_{idx}",
                    "type": "function",
                    "function": {"name": tc["name"], "arguments": tc["arguments"]}
                })

            asst_msg = {"role": "assistant", "tool_calls": tc_list}
            if round_content:
                asst_msg["content"] = round_content
            messages.append(asst_msg)

            for tc in tc_list:
                fn_name = tc["function"]["name"]
                try:
                    args = json.loads(tc["function"]["arguments"])
                except json.JSONDecodeError:
                    args = {}

                yield f"data: {json.dumps({'type': 'tool_use', 'name': fn_name, 'args': args})}\n\n"
                result = run_tool(fn_name, args)
                yield f"data: {json.dumps({'type': 'tool_done', 'name': fn_name})}\n\n"

                messages.append({
                    "role": "tool",
                    "tool_call_id": tc["id"],
                    "content": result
                })

        if full_content:
            history.append({"role": "assistant", "content": full_content})
            if len(history) > 20:
                history = history[-20:]

        yield f"data: {json.dumps({'type': 'done'})}\n\n"

    return Response(
        stream_with_context(generate()),
        content_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
            "Connection": "keep-alive"
        }
    )


@app.route("/clear", methods=["POST"])
def clear():
    global history
    history = []
    return jsonify({"status": "cleared"})


if __name__ == "__main__":
    print(f"OpenCode — http://localhost:{PORT}")
    app.run(host=HOST, port=PORT, debug=True)
