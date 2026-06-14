// 全局状态
let currentSessionId = null;
let conversations = [];
let currentModel = 'ollama';
let userTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone; // 默认浏览器时区
let clockTimer = null;
let weatherTimer = null;
let userCoords = { latitude: null, longitude: null }; // IP定位坐标

// DOM元素
const messagesContainer = document.getElementById('messagesContainer');
const messageInput = document.getElementById('messageInput');
const sendBtn = document.getElementById('sendBtn');
const newChatBtn = document.getElementById('newChatBtn');
const conversationList = document.getElementById('conversationList');
const welcomeScreen = document.getElementById('welcomeScreen');
const modelSelect = document.getElementById('modelSelect');

// 初始化
document.addEventListener('DOMContentLoaded', () => {
    loadCurrentUser();
    loadConversations();
    loadCurrentModel();
    setupEventListeners();
    setupParamListeners();
    loadTimezoneAndStartClock();
});

// 加载当前用户信息
async function loadCurrentUser() {
    try {
        const resp = await fetch('/api/auth/user');
        const data = await resp.json();
        if (!data.loggedIn) {
            window.location.href = '/login';
            return;
        }
        document.getElementById('userName').textContent = data.username;
    } catch (e) {
        window.location.href = '/login';
    }
}

// 退出登录
async function doLogout() {
    try {
        await fetch('/api/auth/logout', { method: 'POST' });
    } catch (e) {}
    window.location.href = '/login';
}

// 处理API响应，检查认证状态
function handleAuthResponse(response) {
    if (response.status === 401 || response.status === 403) {
        window.location.href = '/login';
        return false;
    }
    return true;
}

// 设置事件监听器
function setupEventListeners() {
    sendBtn.addEventListener('click', sendMessage);
    messageInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            if (!sendBtn.disabled) sendMessage();
        }
    });
    messageInput.addEventListener('input', () => {
        updateSendButtonState();
        autoResizeTextarea();
    });
    newChatBtn.addEventListener('click', createNewConversation);
    modelSelect.addEventListener('change', handleModelSwitch);
}

// 设置参数滑块实时显示
function setupParamListeners() {
    const temp = document.getElementById('paramTemperature');
    const topP = document.getElementById('paramTopP');
    temp.addEventListener('input', () => {
        document.getElementById('valTemperature').textContent = temp.value;
    });
    topP.addEventListener('input', () => {
        document.getElementById('valTopP').textContent = topP.value;
    });
}

function autoResizeTextarea() {
    messageInput.style.height = 'auto';
    messageInput.style.height = Math.min(messageInput.scrollHeight, 200) + 'px';
}

function updateSendButtonState() {
    sendBtn.disabled = messageInput.value.trim().length === 0;
}

// 创建新会话
async function createNewConversation() {
    try {
        const response = await fetch('/api/chat/conversation', { method: 'POST' });
        if (!handleAuthResponse(response)) return;
        if (!response.ok) throw new Error('创建会话失败');
        const data = await response.json();
        currentSessionId = data.sessionId;
        clearMessages();
        showWelcomeScreen();
        await loadConversations();
        messageInput.focus();
    } catch (error) {
        console.error('创建会话错误:', error);
        showError('创建会话失败，请重试');
    }
}

// 加载会话列表（从服务端获取）
async function loadConversations() {
    try {
        const response = await fetch('/api/chat/conversations');
        if (!handleAuthResponse(response)) return;
        if (!response.ok) throw new Error('加载会话列表失败');
        const data = await response.json();
        conversations = data.conversations || [];
        renderConversationList();
    } catch (error) {
        console.error('加载会话列表错误:', error);
    }
}

// 渲染会话列表
function renderConversationList() {
    conversationList.innerHTML = '';
    conversations.forEach(conv => {
        const item = document.createElement('div');
        item.className = `conversation-item ${conv.id === currentSessionId ? 'active' : ''}`;
        item.onclick = () => selectConversation(conv.id);

        const title = document.createElement('span');
        title.className = 'conversation-title';
        title.textContent = conv.title || '新对话';

        const deleteBtn = document.createElement('button');
        deleteBtn.className = 'delete-conversation-btn';
        deleteBtn.innerHTML = `
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="3 6 5 6 21 6"></polyline>
                <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
            </svg>
        `;
        deleteBtn.onclick = (e) => {
            e.stopPropagation();
            deleteConversation(conv.id);
        };

        item.appendChild(title);
        item.appendChild(deleteBtn);
        conversationList.appendChild(item);
    });
}

// 选择会话
async function selectConversation(sessionId) {
    currentSessionId = sessionId;
    renderConversationList();
    await loadMessageHistory(sessionId);
}

// 删除会话
async function deleteConversation(sessionId) {
    if (!confirm('确定要删除这个会话吗？')) return;
    try {
        const response = await fetch(`/api/chat/conversation/${sessionId}`, { method: 'DELETE' });
        if (!handleAuthResponse(response)) return;
        const data = await response.json();
        if (!data.success) {
            showError(data.message || '删除失败');
            return;
        }
        conversations = conversations.filter(c => c.id !== sessionId);
        if (currentSessionId === sessionId) {
            currentSessionId = null;
            clearMessages();
            showWelcomeScreen();
        }
        renderConversationList();
    } catch (error) {
        console.error('删除会话错误:', error);
        showError('删除会话失败，请重试');
    }
}

// 发送消息
async function sendMessage() {
    const message = messageInput.value.trim();
    if (!message) return;

    if (!currentSessionId) {
        await createNewConversation();
        if (!currentSessionId) return; // 创建失败则退出
    }

    hideWelcomeScreen();
    addMessageToUI('user', message);
    messageInput.value = '';
    updateSendButtonState();
    autoResizeTextarea();
    showTypingIndicator();

    try {
        const response = await fetch('/api/chat/send', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sessionId: currentSessionId, message })
        });
        if (!handleAuthResponse(response)) return;
        if (!response.ok) throw new Error('发送消息失败');

        const data = await response.json();
        hideTypingIndicator();
        addMessageToUI('assistant', data.response);

        // 如果是第一条消息，更新会话标题
        const sessionMessages = document.querySelectorAll('.message');
        if (sessionMessages.length === 2) {
            // 更新会话列表中的标题
            const conv = conversations.find(c => c.id === currentSessionId);
            const title = message.substring(0, 30) + (message.length > 30 ? '...' : '');
            if (conv) {
                conv.title = title;
                renderConversationList();
            }
        }
        scrollToBottom();
    } catch (error) {
        console.error('发送消息错误:', error);
        hideTypingIndicator();
        showError('发送消息失败，请重试');
    }
}

// 添加消息到UI
function addMessageToUI(role, content) {
    const messageDiv = document.createElement('div');
    messageDiv.className = `message message-${role}`;
    const bubble = document.createElement('div');
    bubble.className = 'message-bubble';
    bubble.textContent = content;
    const time = document.createElement('div');
    time.className = 'message-time';
    time.textContent = formatTime(new Date());
    messageDiv.appendChild(bubble);
    messageDiv.appendChild(time);
    messagesContainer.appendChild(messageDiv);
    scrollToBottom();
}

function showTypingIndicator() {
    const typingDiv = document.createElement('div');
    typingDiv.className = 'message message-assistant';
    typingDiv.id = 'typingIndicator';
    const bubble = document.createElement('div');
    bubble.className = 'message-bubble';
    const indicator = document.createElement('div');
    indicator.className = 'typing-indicator';
    indicator.innerHTML = `<div class="typing-dot"></div><div class="typing-dot"></div><div class="typing-dot"></div>`;
    bubble.appendChild(indicator);
    typingDiv.appendChild(bubble);
    messagesContainer.appendChild(typingDiv);
    scrollToBottom();
}

function hideTypingIndicator() {
    const el = document.getElementById('typingIndicator');
    if (el) el.remove();
}

// 加载消息历史
async function loadMessageHistory(sessionId) {
    try {
        const response = await fetch(`/api/chat/history/${sessionId}`);
        if (!handleAuthResponse(response)) return;
        if (!response.ok) throw new Error('加载历史记录失败');
        const data = await response.json();
        clearMessages();
        if (data.messages && data.messages.length > 0) {
            hideWelcomeScreen();
            data.messages.forEach(msg => addMessageToUI(msg.role, msg.content));
        } else {
            showWelcomeScreen();
        }
    } catch (error) {
        console.error('加载历史记录错误:', error);
        showError('加载历史记录失败');
    }
}

function clearMessages() { messagesContainer.innerHTML = ''; }

function showWelcomeScreen() {
    if (!document.getElementById('welcomeScreen')) {
        const welcome = document.createElement('div');
        welcome.id = 'welcomeScreen';
        welcome.className = 'welcome-screen';
        welcome.innerHTML = `
            <div class="welcome-content">
                <h1>Spring AI Chat</h1>
                <p>智能对话助手 - 支持离线模式、DeepSeek 和 OpenAI</p>
            </div>
        `;
        messagesContainer.appendChild(welcome);
    }
}

function hideWelcomeScreen() {
    const el = document.getElementById('welcomeScreen');
    if (el) el.remove();
}

function scrollToBottom() { messagesContainer.scrollTop = messagesContainer.scrollHeight; }

function formatTime(date) {
    return `${date.getHours().toString().padStart(2,'0')}:${date.getMinutes().toString().padStart(2,'0')}`;
}

// ==================== 时区与实时时钟 ====================

// 从后端获取时区并启动时钟
async function loadTimezoneAndStartClock() {
    try {
        const resp = await fetch('/api/timezone');
        if (resp.ok) {
            const data = await resp.json();
            if (data.success && data.timezone) {
                userTimezone = data.timezone;
            }
            if (data.latitude != null && data.longitude != null) {
                userCoords = { latitude: data.latitude, longitude: data.longitude };
            }
        }
    } catch (e) {
        console.warn('获取时区失败，使用浏览器默认时区:', e);
    }
    startClock();
    // 如果后端未返回坐标，尝试浏览器地理定位
    if (userCoords.latitude == null) {
        requestBrowserLocation();
    } else {
        loadWeather();
    }
}

// 浏览器地理定位回退（私有IP时使用）
function requestBrowserLocation() {
    if (!navigator.geolocation) {
        console.warn('浏览器不支持地理定位');
        return;
    }
    navigator.geolocation.getCurrentPosition(
        (pos) => {
            userCoords = { latitude: pos.coords.latitude, longitude: pos.coords.longitude };
            loadWeather();
        },
        (err) => {
            console.warn('地理定位失败:', err.message);
        },
        { timeout: 8000, maximumAge: 600000 }
    );
}

// 启动实时时钟
function startClock() {
    updateClock();
    if (clockTimer) clearInterval(clockTimer);
    clockTimer = setInterval(updateClock, 1000);
}

// 更新时钟显示
function updateClock() {
    const now = new Date();
    const timeEl = document.getElementById('datetimeTime');
    const dateEl = document.getElementById('datetimeDate');
    if (!timeEl || !dateEl) return;

    // 使用 Intl 格式化时间
    const timeStr = new Intl.DateTimeFormat('zh-CN', {
        timeZone: userTimezone,
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false
    }).format(now);

    // 使用 Intl 格式化日期 + 星期
    const dateStr = new Intl.DateTimeFormat('zh-CN', {
        timeZone: userTimezone,
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        weekday: 'long'
    }).format(now);

    timeEl.textContent = timeStr;
    dateEl.textContent = dateStr;
}

// ==================== 天气显示（Open-Meteo） ====================

// WMO 天气代码映射：[emoji, 中文描述]
const WMO_CODES = {
    0:  ['☀️', '晴'],
    1:  ['🌤️', '大部晴朗'],
    2:  ['⛅', '多云'],
    3:  ['☁️', '阴'],
    45: ['🌫️', '雾'],
    48: ['🌫️', '霜雾'],
    51: ['🌦️', '小毛毛雨'],
    53: ['🌦️', '毛毛雨'],
    55: ['🌦️', '大毛毛雨'],
    56: ['🌧️', '冻毛毛雨'],
    57: ['🌧️', '冻雨'],
    61: ['🌧️', '小雨'],
    63: ['🌧️', '中雨'],
    65: ['🌧️', '大雨'],
    66: ['🌧️', '小冻雨'],
    67: ['🌧️', '大冻雨'],
    71: ['🌨️', '小雪'],
    73: ['🌨️', '中雪'],
    75: ['❄️', '大雪'],
    77: ['🌨️', '雪粒'],
    80: ['🌧️', '小阵雨'],
    81: ['🌧️', '阵雨'],
    82: ['⛈️', '大阵雨'],
    85: ['🌨️', '小阵雪'],
    86: ['❄️', '大阵雪'],
    95: ['⛈️', '雷暴'],
    96: ['⛈️', '雷暴冰電'],
    99: ['⛈️', '强雷暴冰電']
};

function getWeatherInfo(code) {
    return WMO_CODES[code] || ['🌡️', '未知'];
}

// 加载并渲染天气，每10分钟刷新一次
async function loadWeather() {
    if (userCoords.latitude == null || userCoords.longitude == null) return;
    const weatherEl = document.getElementById('datetimeWeather');
    if (!weatherEl) return;

    try {
        const url = `https://api.open-meteo.com/v1/forecast?latitude=${userCoords.latitude}&longitude=${userCoords.longitude}&current=temperature_2m,weather_code&timezone=auto`;
        const resp = await fetch(url);
        if (!resp.ok) throw new Error('天气API请求失败');
        const data = await resp.json();
        const temp = Math.round(data.current.temperature_2m);
        const code = data.current.weather_code;
        const [icon, desc] = getWeatherInfo(code);
        weatherEl.innerHTML = `<span class="weather-icon">${icon}</span><span class="weather-temp">${temp}°C</span><span class="weather-desc">${desc}</span>`;
    } catch (e) {
        console.warn('加载天气失败:', e);
        weatherEl.innerHTML = '';
    }

    // 每10分钟刷新天气
    if (weatherTimer) clearInterval(weatherTimer);
    weatherTimer = setInterval(loadWeather, 10 * 60 * 1000);
}

function showError(message) { alert(message); }

// 加载当前模型
async function loadCurrentModel() {
    try {
        const response = await fetch('/api/model/current');
        if (response.ok) {
            const data = await response.json();
            currentModel = data.model;
            modelSelect.value = currentModel;
        }
    } catch (error) {
        console.error('加载模型信息失败:', error);
    }
}

// 处理模型切换
async function handleModelSwitch() {
    const newModel = modelSelect.value;
    if (newModel === currentModel) return;
    try {
        const response = await fetch('/api/model/switch', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ model: newModel })
        });
        const data = await response.json();
        if (data.success) {
            currentModel = newModel;
            showSuccess(data.message);
            loadParamPreset();  // 切换模型后加载该模型的参数预设
        } else {
            showError(data.message);
            modelSelect.value = currentModel;
        }
    } catch (error) {
        console.error('切换模型失败:', error);
        showError('切换模型失败，请重试');
        modelSelect.value = currentModel;
    }
}

function showSuccess(message) {
    const toast = document.createElement('div');
    toast.textContent = message;
    toast.style.cssText = `
        position:fixed;top:20px;right:20px;background:#10b981;color:white;
        padding:12px 24px;border-radius:8px;box-shadow:0 4px 6px rgba(0,0,0,0.1);
        z-index:1000;animation:slideIn 0.3s ease;
    `;
    document.body.appendChild(toast);
    setTimeout(() => {
        toast.style.animation = 'slideOut 0.3s ease';
        setTimeout(() => toast.remove(), 300);
    }, 2000);
}

// ==================== 参数预设功能 ====================

// 展开/收起参数面板
function toggleParamPanel() {
    const body = document.getElementById('paramBody');
    const arrow = document.getElementById('paramArrow');
    if (body.style.display === 'none') {
        body.style.display = 'block';
        arrow.style.transform = 'rotate(180deg)';
        loadParamPreset();
    } else {
        body.style.display = 'none';
        arrow.style.transform = 'rotate(0deg)';
    }
}

// 加载当前模型的参数预设
async function loadParamPreset() {
    try {
        const response = await fetch(`/api/model/params/${currentModel}`);
        if (!response.ok) return;
        const data = await response.json();
        if (data.success && data.preset) {
            const p = data.preset;
            if (p.temperature != null) {
                document.getElementById('paramTemperature').value = p.temperature;
                document.getElementById('valTemperature').textContent = p.temperature;
            }
            if (p.maxTokens != null) {
                document.getElementById('paramMaxTokens').value = p.maxTokens;
            }
            if (p.topP != null) {
                document.getElementById('paramTopP').value = p.topP;
                document.getElementById('valTopP').textContent = p.topP;
            }
            if (p.topK != null) {
                document.getElementById('paramTopK').value = p.topK;
            }
        }
    } catch (e) {
        console.error('加载参数预设失败:', e);
    }
}

// 保存参数预设
async function saveParamPreset() {
    const preset = {
        temperature: parseFloat(document.getElementById('paramTemperature').value),
        maxTokens: parseInt(document.getElementById('paramMaxTokens').value),
        topP: parseFloat(document.getElementById('paramTopP').value),
        topK: parseInt(document.getElementById('paramTopK').value)
    };
    try {
        const response = await fetch(`/api/model/params/${currentModel}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(preset)
        });
        const data = await response.json();
        if (data.success) {
            showSuccess(data.message);
        } else {
            showError(data.message || '保存失败');
        }
    } catch (e) {
        console.error('保存参数预设失败:', e);
        showError('保存失败，请重试');
    }
}
