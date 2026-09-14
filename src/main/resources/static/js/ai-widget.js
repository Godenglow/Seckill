/**
 * AI 助手悬浮入口。
 *
 * 在页面右下角放一个按钮，点一下弹出对话面板（面板里是一个指向 /ai_chat.htm 的 iframe）。
 * 用 iframe 而不是把对话逻辑复制一份，是为了只有一处需要维护；
 * iframe 首次展开时才创建，避免每个页面访问都白白加载一次聊天页。
 *
 * 引入方式：在页面末尾加 <script src="/js/ai-widget.js"></script>
 */
(function () {
    if (window.__aiWidgetLoaded) {
        return;   // 防止同一个页面被重复引入
    }
    window.__aiWidgetLoaded = true;

    var CSS = [
        '.aiw-btn{position:fixed;right:24px;bottom:24px;z-index:9999;padding:12px 20px;border:0;',
        'border-radius:24px;background:#4a90e2;color:#fff;font-size:14px;cursor:pointer;',
        'box-shadow:0 4px 14px rgba(0,0,0,.22);font-family:-apple-system,"Microsoft YaHei",sans-serif;}',
        '.aiw-btn:hover{background:#3d80cf;}',
        '.aiw-panel{position:fixed;right:24px;bottom:24px;z-index:9999;display:none;',
        'width:390px;height:560px;max-width:calc(100vw - 32px);max-height:calc(100vh - 48px);',
        'background:#fff;border-radius:10px;overflow:hidden;box-shadow:0 8px 30px rgba(0,0,0,.28);',
        'font-family:-apple-system,"Microsoft YaHei",sans-serif;}',
        '.aiw-panel.aiw-open{display:flex;flex-direction:column;}',
        '.aiw-head{display:flex;align-items:center;justify-content:space-between;padding:10px 14px;',
        'background:#4a90e2;color:#fff;font-size:14px;flex:0 0 auto;}',
        '.aiw-close{color:#fff;text-decoration:none;font-size:20px;line-height:1;padding:0 4px;cursor:pointer;}',
        '.aiw-body{flex:1 1 auto;overflow:hidden;}',
        '.aiw-frame{width:100%;height:100%;border:0;display:block;}',
    ].join('');

    function build() {
        var style = document.createElement('style');
        style.textContent = CSS;
        document.head.appendChild(style);

        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'aiw-btn';
        btn.textContent = 'AI 助手';
        btn.title = '问库存 / 活动时间 / 我的订单';

        var panel = document.createElement('div');
        panel.className = 'aiw-panel';

        var head = document.createElement('div');
        head.className = 'aiw-head';
        var title = document.createElement('span');
        title.textContent = '秒杀助手';
        var close = document.createElement('a');
        close.className = 'aiw-close';
        close.href = 'javascript:void(0)';
        close.textContent = '×';
        head.appendChild(title);
        head.appendChild(close);

        var body = document.createElement('div');
        body.className = 'aiw-body';

        panel.appendChild(head);
        panel.appendChild(body);

        var loaded = false;
        function openPanel() {
            if (!loaded) {
                var frame = document.createElement('iframe');
                frame.className = 'aiw-frame';
                // embed=1：让聊天页隐藏自带的页头与说明文字，只留对话区
                frame.src = '/ai_chat.htm?embed=1';
                body.appendChild(frame);
                loaded = true;
            }
            panel.className = 'aiw-panel aiw-open';
            btn.style.display = 'none';
        }
        function closePanel() {
            panel.className = 'aiw-panel';
            btn.style.display = '';
        }

        btn.onclick = openPanel;
        close.onclick = closePanel;

        document.body.appendChild(btn);
        document.body.appendChild(panel);
    }

    // 本文件可能被放在 </body> 之后，此时 body 未必就绪
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', build);
    } else {
        build();
    }
})();
