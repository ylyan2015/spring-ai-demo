// 全局状态
let currentSessionId = null;
let conversations = [];

// DOM元素
const messagesContainer = document.getElementById('messagesContainer');
const messageInput = document.getElementById('messageInput');
const sendBtn = document.getElementById('sendBtn');
const newChatBtn = document.getElementById('newChatBtn');
const conversationList = document.getElementById('conversationList');
const welcomeScreen = document.getElementById('welcomeScreen');

// 初始化
document.addEventListener('DOMContentLoaded', () => {
    loadConversations();
    setupEventListeners();
});

// 设置事件监听器
function setupEventListeners() {
    // 发送按钮点击
    sendBtn.addEventListener('click', sendMessage);

    // 输入框键盘事件
    messageInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            if (!sendBtn.disabled) {
                sendMessage();
            }
        }
    });

    // 输入框内容变化
    messageInput.addEventListener('input', () => {
        updateSendButtonState();
        autoResizeTextarea();
    });

    // 新对话按钮
    newChatBtn.addEventListener('click', createNewConversation);
}

// 自动调整文本框高度
function autoResizeTextarea() {
    messageInput.style.height = 'auto';
    messageInput.style.height = Math.min(messageInput.scrollHeight, 200) + 'px';
}

// 更新发送按钮状态
function updateSendButtonState() {
    const hasText = messageInput.value.trim().length > 0;
    sendBtn.disabled = !hasText;
}

// 创建新会话
async function createNewConversation() {
    try {
        const response = await fetch('/api/chat/conversation', {
            method: 'POST'
        });
        
        if (!response.ok) {
            throw new Error('创建会话失败');
        }

        const data = await response.json();
        currentSessionId = data.sessionId;
        
        // 清空消息区域
        clearMessages();
        showWelcomeScreen();
        
        // 重新加载会话列表
        await loadConversations();
        
        // 聚焦输入框
        messageInput.focus();
        
    } catch (error) {
        console.error('创建会话错误:', error);
        showError('创建会话失败，请重试');
    }
}

// 加载会话列表
async function loadConversations() {
    try {
        // 这里简化处理，实际应该从后端获取会话列表
        // 目前使用localStorage存储会话ID列表
        const savedSessions = localStorage.getItem('conversations');
        if (savedSessions) {
            conversations = JSON.parse(savedSessions);
            renderConversationList();
        }
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
    if (!confirm('确定要删除这个会话吗？')) {
        return;
    }
    
    try {
        const response = await fetch(`/api/chat/conversation/${sessionId}`, {
            method: 'DELETE'
        });
        
        if (!response.ok) {
            throw new Error('删除会话失败');
        }
        
        // 从列表中移除
        conversations = conversations.filter(c => c.id !== sessionId);
        localStorage.setItem('conversations', JSON.stringify(conversations));
        
        // 如果删除的是当前会话，清空消息
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
    
    // 如果没有会话ID，创建新会话
    if (!currentSessionId) {
        await createNewConversation();
    }
    
    // 隐藏欢迎屏幕
    hideWelcomeScreen();
    
    // 添加用户消息到界面
    addMessageToUI('user', message);
    
    // 清空输入框
    messageInput.value = '';
    updateSendButtonState();
    autoResizeTextarea();
    
    // 显示加载动画
    showTypingIndicator();
    
    try {
        const response = await fetch('/api/chat/send', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                sessionId: currentSessionId,
                message: message
            })
        });
        
        if (!response.ok) {
            throw new Error('发送消息失败');
        }
        
        const data = await response.json();
        
        // 隐藏加载动画
        hideTypingIndicator();
        
        // 添加AI回复到界面
        addMessageToUI('assistant', data.response);
        
        // 如果是第一条消息，更新会话标题
        const sessionMessages = document.querySelectorAll('.message');
        if (sessionMessages.length === 2) {
            updateConversationTitle(currentSessionId, message);
        }
        
        // 滚动到底部
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

// 显示加载动画
function showTypingIndicator() {
    const typingDiv = document.createElement('div');
    typingDiv.className = 'message message-assistant';
    typingDiv.id = 'typingIndicator';
    
    const bubble = document.createElement('div');
    bubble.className = 'message-bubble';
    
    const indicator = document.createElement('div');
    indicator.className = 'typing-indicator';
    indicator.innerHTML = `
        <div class="typing-dot"></div>
        <div class="typing-dot"></div>
        <div class="typing-dot"></div>
    `;
    
    bubble.appendChild(indicator);
    typingDiv.appendChild(bubble);
    messagesContainer.appendChild(typingDiv);
    
    scrollToBottom();
}

// 隐藏加载动画
function hideTypingIndicator() {
    const indicator = document.getElementById('typingIndicator');
    if (indicator) {
        indicator.remove();
    }
}

// 加载消息历史
async function loadMessageHistory(sessionId) {
    try {
        const response = await fetch(`/api/chat/history/${sessionId}`);
        
        if (!response.ok) {
            throw new Error('加载历史记录失败');
        }
        
        const data = await response.json();
        
        // 清空当前消息
        clearMessages();
        
        // 如果有消息，隐藏欢迎屏幕
        if (data.messages && data.messages.length > 0) {
            hideWelcomeScreen();
            
            // 添加所有历史消息
            data.messages.forEach(msg => {
                addMessageToUI(msg.role, msg.content);
            });
        } else {
            showWelcomeScreen();
        }
        
    } catch (error) {
        console.error('加载历史记录错误:', error);
        showError('加载历史记录失败');
    }
}

// 清空消息
function clearMessages() {
    messagesContainer.innerHTML = '';
}

// 显示欢迎屏幕
function showWelcomeScreen() {
    if (!document.getElementById('welcomeScreen')) {
        const welcome = document.createElement('div');
        welcome.id = 'welcomeScreen';
        welcome.className = 'welcome-screen';
        welcome.innerHTML = `
            <div class="welcome-content">
                <h1>Spring AI Chat</h1>
                <p>基于 Ollama + Qwen2.5 的智能对话助手</p>
            </div>
        `;
        messagesContainer.appendChild(welcome);
    }
}

// 隐藏欢迎屏幕
function hideWelcomeScreen() {
    const welcome = document.getElementById('welcomeScreen');
    if (welcome) {
        welcome.remove();
    }
}

// 更新会话标题
function updateConversationTitle(sessionId, title) {
    const conv = conversations.find(c => c.id === sessionId);
    if (conv) {
        conv.title = title.substring(0, 30) + (title.length > 30 ? '...' : '');
        localStorage.setItem('conversations', JSON.stringify(conversations));
        renderConversationList();
    } else {
        conversations.push({
            id: sessionId,
            title: title.substring(0, 30) + (title.length > 30 ? '...' : '')
        });
        localStorage.setItem('conversations', JSON.stringify(conversations));
        renderConversationList();
    }
}

// 滚动到底部
function scrollToBottom() {
    messagesContainer.scrollTop = messagesContainer.scrollHeight;
}

// 格式化时间
function formatTime(date) {
    const hours = date.getHours().toString().padStart(2, '0');
    const minutes = date.getMinutes().toString().padStart(2, '0');
    return `${hours}:${minutes}`;
}

// 显示错误提示
function showError(message) {
    // 简单的alert，可以改进为更优雅的toast提示
    alert(message);
}
