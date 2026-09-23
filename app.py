# 超小型文字群聊应用后端
# 技术栈：Flask + SQLite + 原生前端 AJAX 轮询
# 功能：仅文字群聊，QQ 号登录，管理员可全账号管理，支持全员禁言

import os
import json
import sqlite3
import threading
import secrets
import base64
from datetime import timedelta
from functools import wraps

from flask import (
    Flask, render_template, request, redirect, url_for,
    flash, session, jsonify, g
)
from werkzeug.security import generate_password_hash, check_password_hash

# 配置项 ===================================================
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATABASE = os.path.join(BASE_DIR, "chat.db")
GROUP_NAME = "life的群组"  # 群聊显示名称

ADMIN_USERNAME = "Administrator life"
ADMIN_PASSWORD = "168188902@qq.com"

app = Flask(__name__)
# secret_key 用于 session 签名，首次启动时自动生成并写入 secret.key
SECRET_KEY_FILE = os.path.join(BASE_DIR, "secret.key")
if os.path.exists(SECRET_KEY_FILE):
    app.secret_key = open(SECRET_KEY_FILE, "r", encoding="utf-8").read().strip()
else:
    app.secret_key = secrets.token_hex(32)
    open(SECRET_KEY_FILE, "w", encoding="utf-8").write(app.secret_key)


# 数据库连接 ================================================
def get_db():
    """获取当前请求的数据库连接，每个请求复用同一连接。"""
    if "db" not in g:
        g.db = sqlite3.connect(DATABASE)
        g.db.row_factory = sqlite3.Row
    return g.db


@app.teardown_appcontext
def close_db(exception):
    """请求结束时关闭数据库连接。"""
    db = g.pop("db", None)
    if db is not None:
        db.close()


# 数据库初始化与迁移 ========================================
def init_db():
    """初始化数据库表：users、messages、settings。"""
    db = sqlite3.connect(DATABASE)
    db.execute("""
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            qq TEXT NOT NULL UNIQUE,
            username TEXT NOT NULL UNIQUE,
            password_hash TEXT NOT NULL,
            role TEXT NOT NULL DEFAULT 'user',
            status TEXT NOT NULL DEFAULT 'pending',
            avatar TEXT DEFAULT NULL,
            public_key TEXT DEFAULT NULL,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """)
    # 已存在的数据库做字段迁移
    try:
        db.execute("ALTER TABLE users ADD COLUMN avatar TEXT DEFAULT NULL")
    except sqlite3.OperationalError:
        pass
    try:
        db.execute("ALTER TABLE users ADD COLUMN public_key TEXT DEFAULT NULL")
    except sqlite3.OperationalError:
        pass
    db.execute("""
        CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            username TEXT NOT NULL,
            content TEXT,
            payload TEXT,
            created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
        )
    """)
    try:
        db.execute("ALTER TABLE messages ADD COLUMN payload TEXT")
    except sqlite3.OperationalError:
        pass
    db.execute("""
        CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        )
    """)
    # 默认不开启全员禁言
    db.execute("INSERT OR IGNORE INTO settings (key, value) VALUES ('mute_all', '0')")

    # 初始化群共享 AES-256 密钥（32 字节随机，base64）。所有 approved 用户共用此密钥加密消息。
    db.execute(
        "INSERT OR IGNORE INTO settings (key, value) "
        "SELECT 'group_aes_key', ? WHERE (SELECT COUNT(*) FROM settings WHERE key='group_aes_key') = 0",
        (base64.b64encode(secrets.token_bytes(32)).decode(),)
    )

    # 创建内置管理员账号，仅当不存在时插入（管理员不进群聊，无需加密密钥）
    admin_hash = generate_password_hash(ADMIN_PASSWORD)
    db.execute("""
        INSERT OR IGNORE INTO users (qq, username, password_hash, role, status)
        VALUES (?, ?, ?, 'admin', 'approved')
    """, ("admin@system", ADMIN_USERNAME, admin_hash))
    db.commit()
    db.close()


def cleanup_old_messages():
    """删除超过 30 天的消息。"""
    db = None
    try:
        db = sqlite3.connect(DATABASE)
        db.execute("DELETE FROM messages WHERE created_at < datetime('now', '-30 days')")
        db.commit()
    except Exception as e:
        print("清理旧消息失败:", e)
    finally:
        if db:
            db.close()


def schedule_cleanup():
    cleanup_old_messages()
    threading.Timer(3600, schedule_cleanup).start()


# 工具函数 =================================================
def current_user():
    """从 session 获取当前登录用户的信息，未登录返回 None。"""
    uid = session.get("user_id")
    if not uid:
        return None
    row = get_db().execute(
        "SELECT id, qq, username, role, status, avatar, public_key FROM users WHERE id = ?", (uid,)
    ).fetchone()
    return dict(row) if row else None


def login_required(f):
    """装饰器：要求已登录（管理员或普通用户均可）。"""
    @wraps(f)
    def wrapped(*args, **kwargs):
        user = current_user()
        if not user:
            if request.path.startswith("/api/") or request.headers.get("X-Requested-With") == "XMLHttpRequest":
                return jsonify({"ok": False, "error": "未登录"}), 401
            flash("请先登录", "error")
            return redirect(url_for("login_page"))
        return f(*args, **kwargs)
    return wrapped


def is_muted():
    """查询当前是否开启全员禁言。"""
    row = get_db().execute(
        "SELECT value FROM settings WHERE key = 'mute_all'"
    ).fetchone()
    return row is not None and row["value"] == "1"


def admin_required(f):
    """装饰器：要求当前用户为已登录管理员。API 返回 JSON，页面重定向。"""
    @wraps(f)
    def wrapped(*args, **kwargs):
        user = current_user()
        if not user or user["role"] != "admin":
            if request.path.startswith("/api/") or request.headers.get("X-Requested-With") == "XMLHttpRequest":
                return jsonify({"ok": False, "error": "需要管理员权限"}), 403
            flash("需要管理员权限", "error")
            return redirect(url_for("login_page"))
        return f(*args, **kwargs)
    return wrapped


def approved_required(f):
    """装饰器：要求已通过审核的普通用户（管理员被排除，不能发消息）。"""
    @wraps(f)
    def wrapped(*args, **kwargs):
        user = current_user()
        if not user:
            return jsonify({"ok": False, "error": "未登录"}), 401
        # 管理员账号仅用于后台管理，禁止进入群聊和发送消息
        if user["role"] == "admin":
            return jsonify({"ok": False, "error": "管理员账号不能发送消息"}), 403
        if user["status"] != "approved":
            return jsonify({"ok": False, "error": "账号正在等待审核"}), 403
        return f(*args, **kwargs)
    return wrapped


# 页面路由 =================================================
@app.route("/")
def login_page():
    """登录/注册/修改用户名/管理员登录综合首页。"""
    user = current_user()
    if user:
        if user["role"] == "admin":
            return redirect(url_for("admin_page"))
        if user["status"] == "approved":
            return redirect(url_for("chat_page"))
        return redirect(url_for("pending_page"))
    return render_template("login.html", group_name=GROUP_NAME)


@app.route("/register", methods=["POST"])
def register():
    """处理注册请求：必填 QQ 号、用户名、密码。"""
    qq = request.form.get("qq", "").strip()
    username = request.form.get("username", "").strip()
    password = request.form.get("password", "")

    if not qq or not username or not password:
        flash("QQ 号、用户名、密码均不能为空", "error")
        return redirect(url_for("login_page"))

    if len(password) < 6:
        flash("密码长度至少 6 位", "error")
        return redirect(url_for("login_page"))

    db = get_db()
    existing = db.execute(
        "SELECT id FROM users WHERE qq = ? OR username = ?", (qq, username)
    ).fetchone()
    if existing:
        flash("该 QQ 号或用户名已被注册", "error")
        return redirect(url_for("login_page"))

    password_hash = generate_password_hash(password)
    db.execute(
        "INSERT INTO users (qq, username, password_hash, role, status) VALUES (?, ?, ?, 'user', 'pending')",
        (qq, username, password_hash),
    )
    db.commit()
    session["user_id"] = db.execute(
        "SELECT id FROM users WHERE username = ?", (username,)
    ).fetchone()["id"]
    flash("注册成功，请等待管理员审核", "success")
    return redirect(url_for("pending_page"))


@app.route("/login", methods=["POST"])
def login():
    """普通用户用 QQ 号 + 密码登录。"""
    qq = request.form.get("qq", "").strip()
    password = request.form.get("password", "")

    db = get_db()
    row = db.execute(
        "SELECT id, qq, username, password_hash, role, status FROM users WHERE qq = ?",
        (qq,),
    ).fetchone()

    if not row or not check_password_hash(row["password_hash"], password):
        flash("QQ 号或密码错误", "error")
        return redirect(url_for("login_page"))

    session["user_id"] = row["id"]
    if row["role"] == "admin":
        return redirect(url_for("admin_page"))
    if row["status"] == "approved":
        return redirect(url_for("chat_page"))
    return redirect(url_for("pending_page"))


@app.route("/admin-login", methods=["POST"])
def admin_login():
    """管理员用账号 + 密码登录。"""
    username = request.form.get("username", "").strip()
    password = request.form.get("password", "")

    db = get_db()
    row = db.execute(
        "SELECT id, username, password_hash, role, status FROM users WHERE username = ? AND role = 'admin'",
        (username,),
    ).fetchone()

    if not row or not check_password_hash(row["password_hash"], password):
        flash("管理员账号或密码错误", "error")
        return redirect(url_for("login_page"))

    session["user_id"] = row["id"]
    return redirect(url_for("admin_page"))


@app.route("/change-username", methods=["POST"])
def change_username():
    """用户输入 QQ 号和密码后修改用户名。"""
    qq = request.form.get("qq", "").strip()
    password = request.form.get("password", "")
    new_username = request.form.get("new_username", "").strip()

    if not qq or not password or not new_username:
        flash("QQ 号、密码、新用户名均不能为空", "error")
        return redirect(url_for("login_page"))

    db = get_db()
    row = db.execute(
        "SELECT id, username, password_hash FROM users WHERE qq = ?", (qq,)
    ).fetchone()

    if not row or not check_password_hash(row["password_hash"], password):
        flash("QQ 号或密码错误", "error")
        return redirect(url_for("login_page"))

    existing = db.execute(
        "SELECT id FROM users WHERE username = ? AND id != ?", (new_username, row["id"])
    ).fetchone()
    if existing:
        flash("该用户名已被占用", "error")
        return redirect(url_for("login_page"))

    old_username = row["username"]
    db.execute("UPDATE users SET username = ? WHERE id = ?", (new_username, row["id"]))
    # 同步更新历史消息中显示的用户名
    db.execute("UPDATE messages SET username = ? WHERE user_id = ?", (new_username, row["id"]))
    db.commit()
    flash(f"用户名已从 {old_username} 修改为 {new_username}", "success")
    return redirect(url_for("login_page"))


@app.route("/logout")
def logout():
    """退出登录。"""
    session.clear()
    return redirect(url_for("login_page"))


@app.route("/pending")
def pending_page():
    """等待审核提示页。"""
    user = current_user()
    if not user:
        return redirect(url_for("login_page"))
    if user["role"] == "admin":
        return redirect(url_for("admin_page"))
    if user["status"] == "approved":
        return redirect(url_for("chat_page"))
    return render_template("pending.html", group_name=GROUP_NAME, username=user["username"])


@app.route("/chat")
def chat_page():
    """群聊主页面，仅普通已审核用户可进。"""
    user = current_user()
    if not user:
        return redirect(url_for("login_page"))
    if user["role"] == "admin":
        flash("管理员账号只能进入管理后台", "warning")
        return redirect(url_for("admin_page"))
    if user["status"] != "approved":
        return redirect(url_for("pending_page"))
    return render_template(
        "chat.html",
        group_name=GROUP_NAME,
        username=user["username"],
        muted=is_muted(),
    )


@app.route("/admin")
@admin_required
def admin_page():
    """管理员后台。"""
    db = get_db()
    users = db.execute(
        "SELECT id, qq, username, role, status, created_at FROM users ORDER BY created_at"
    ).fetchall()
    messages = db.execute(
        """
        SELECT m.id, m.user_id, m.username, m.content,
               strftime('%Y-%m-%d %H:%M:%S', m.created_at, 'localtime') AS created_at
        FROM messages m ORDER BY m.created_at DESC LIMIT 200
        """
    ).fetchall()
    mute_value = db.execute("SELECT value FROM settings WHERE key = 'mute_all'").fetchone()["value"]
    return render_template(
        "admin.html",
        group_name=GROUP_NAME,
        users=[dict(r) for r in users],
        messages=[dict(r) for r in messages],
        muted=mute_value == "1",
    )


# API 接口（供原生 Android 客户端使用）======================
@app.route("/api/register", methods=["POST"])
def api_register():
    """JSON API：用户注册。"""
    qq = request.form.get("qq", "").strip()
    username = request.form.get("username", "").strip()
    password = request.form.get("password", "")
    if not qq or not username or not password:
        return jsonify({"ok": False, "error": "QQ 号、用户名、密码均不能为空"})
    if len(password) < 6:
        return jsonify({"ok": False, "error": "密码长度至少 6 位"})

    db = get_db()
    existing_qq = db.execute("SELECT id FROM users WHERE qq = ?", (qq,)).fetchone()
    if existing_qq:
        return jsonify({"ok": False, "error": "该账号已注册"})
    existing_name = db.execute("SELECT id FROM users WHERE username = ?", (username,)).fetchone()
    if existing_name:
        return jsonify({"ok": False, "error": "该用户名已被占用"})

    public_key = request.form.get("public_key", "")
    # 公钥缺失时允许注册，登录后再补传（兼容旧版或未启用端加密的客户端）
    public_key = public_key or ""

    password_hash = generate_password_hash(password)
    db.execute(
        "INSERT INTO users (qq, username, password_hash, role, status, avatar, public_key) VALUES (?, ?, ?, 'user', 'pending', NULL, ?)",
        (qq, username, password_hash, public_key),
    )
    db.commit()
    session["user_id"] = db.execute(
        "SELECT id FROM users WHERE username = ?", (username,)
    ).fetchone()["id"]
    return jsonify({"ok": True})


@app.route("/api/login", methods=["POST"])
def api_login():
    """JSON API：普通用户 QQ 登录。"""
    qq = request.form.get("qq", "").strip()
    password = request.form.get("password", "")
    db = get_db()
    row = db.execute(
        "SELECT id, qq, username, password_hash, role, status FROM users WHERE qq = ?",
        (qq,),
    ).fetchone()

    if not row:
        return jsonify({"ok": False, "error": "该 QQ 号未注册"})
    if row["role"] == "admin":
        return jsonify({"ok": False, "error": "请使用管理员登录入口"})
    if not check_password_hash(row["password_hash"], password):
        return jsonify({"ok": False, "error": "密码错误"})
    if row["status"] != "approved":
        return jsonify({"ok": False, "error": "该账号申请等待同意"})

    session["user_id"] = row["id"]
    session.permanent = True
    app.permanent_session_lifetime = timedelta(days=3)
    return jsonify({"ok": True, "username": row["username"], "status": row["status"], "avatar": row["avatar"]})


@app.route("/api/admin-login", methods=["POST"])
def api_admin_login():
    """JSON API：管理员登录（兼容旧接口）。"""
    username = request.form.get("username", "").strip()
    password = request.form.get("password", "")
    if not username or not password:
        return jsonify({"ok": False, "error": "请填写完整"})

    db = get_db()
    row = db.execute(
        "SELECT id, username, password_hash, role FROM users WHERE username = ? AND role = 'admin'",
        (username,),
    ).fetchone()
    if not row:
        return jsonify({"ok": False, "error": "管理员账号不存在"})
    if not check_password_hash(row["password_hash"], password):
        return jsonify({"ok": False, "error": "管理员密码错误"})
    session["user_id"] = row["id"]
    session.permanent = True
    app.permanent_session_lifetime = timedelta(days=3)
    return jsonify({"ok": True})


@app.route("/api/unified-login", methods=["POST"])
def api_unified_login():
    """JSON API：统一账号/QQ号登录，自动区分管理员与普通用户。"""
    account = request.form.get("account", "").strip()
    password = request.form.get("password", "")
    if not account or not password:
        return jsonify({"ok": False, "error": "请填写账号和密码"})

    db = get_db()
    row = db.execute(
        """
        SELECT id, username, qq, role, status, password_hash, avatar, public_key
        FROM users
        WHERE (qq = ? AND role = 'admin')
           OR (qq = ? AND role = 'user')
           OR (username = ? AND role = 'admin')
           OR (username = ? AND role = 'user')
        """,
        (account, account, account, account),
    ).fetchone()

    if not row:
        return jsonify({"ok": False, "error": "账号不存在"})
    if not check_password_hash(row["password_hash"], password):
        return jsonify({"ok": False, "error": "密码错误"})
    if row["role"] == "user" and row["status"] != "approved":
        return jsonify({"ok": False, "error": "该账号申请等待同意"})

    session["user_id"] = row["id"]
    session.permanent = True
    app.permanent_session_lifetime = timedelta(days=3)
    return jsonify({
        "ok": True,
        "id": row["id"],
        "username": row["username"],
        "role": row["role"],
        "status": row["status"],
        "avatar": row["avatar"],
        "public_key": row["public_key"],
    })


@app.route("/api/change-username", methods=["POST"])
@login_required
def api_change_username():
    """JSON API：已登录用户直接修改用户名，无需再次验证密码。"""
    new_username = (request.form.get("new_username") or "").strip()
    if not new_username:
        return jsonify({"ok": False, "error": "新用户名不能为空"})

    db = get_db()
    user_id = session["user_id"]
    existing = db.execute(
        "SELECT id FROM users WHERE username = ? AND id != ?", (new_username, user_id)
    ).fetchone()
    if existing:
        return jsonify({"ok": False, "error": "该用户名已被占用"})

    old_row = db.execute("SELECT username FROM users WHERE id = ?", (user_id,)).fetchone()
    old_name = old_row["username"] if old_row else ""
    db.execute("UPDATE users SET username = ? WHERE id = ?", (new_username, user_id))
    if old_name:
        db.execute("UPDATE messages SET username = ? WHERE user_id = ? AND username = ?",
                   (new_username, user_id, old_name))
    db.commit()
    new_row = db.execute(
        "SELECT id, username, qq, role, status, avatar FROM users WHERE id = ?", (user_id,)
    ).fetchone()
    return jsonify({"ok": True, "user": dict(new_row)})


@app.route("/api/me")
@login_required
def api_me():
    """JSON API：获取当前登录用户信息。"""
    db = get_db()
    row = db.execute(
        "SELECT id, qq, username, role, status, avatar, public_key, created_at FROM users WHERE id = ?",
        (session["user_id"],)
    ).fetchone()
    if not row:
        return jsonify({"ok": False, "error": "用户不存在"}), 404
    user = dict(row)
    user["avatar"] = user.get("avatar")
    return jsonify({"ok": True, "user": user})


@app.route("/api/update-avatar", methods=["POST"])
@login_required
def api_update_avatar():
    """JSON API：上传 base64 头像，限制大小不超过 500KB。"""
    avatar = (request.form.get("avatar") or "").strip()
    if not avatar:
        return jsonify({"ok": False, "error": "头像数据不能为空"})
    # 简单限制 base64 字符串长度（约 500KB 图片编码后约 680KB）
    if len(avatar) > 700_000:
        return jsonify({"ok": False, "error": "头像不能超过 500KB"})
    db = get_db()
    db.execute("UPDATE users SET avatar = ? WHERE id = ?", (avatar, session["user_id"]))
    db.commit()
    return jsonify({"ok": True})


@app.route("/api/admin/users")
@admin_required
def api_admin_users():
    """JSON API：返回所有用户列表和禁言状态。"""
    db = get_db()
    rows = db.execute(
        "SELECT id, qq, username, role, status, avatar, public_key, created_at FROM users ORDER BY created_at"
    ).fetchall()
    mute_value = db.execute("SELECT value FROM settings WHERE key = 'mute_all'").fetchone()["value"]
    return jsonify({
        "ok": True,
        "users": [dict(r) for r in rows],
        "muted": mute_value == "1",
    })


@app.route("/api/messages")
@approved_required
def api_messages():
    """轮询接口：返回新消息，全员禁言状态同步返回。"""
    last_id = request.args.get("last_id", type=int, default=0)
    db = get_db()
    rows = db.execute(
        """
        SELECT m.id, m.user_id, m.username, m.content, m.payload,
               strftime('%Y-%m-%d %H:%M:%S', m.created_at) AS created_at,
               u.avatar
        FROM messages m
        LEFT JOIN users u ON u.id = m.user_id
        WHERE m.id > ?
        ORDER BY m.id ASC
        """,
        (last_id,),
    ).fetchall()
    return jsonify({"messages": [dict(r) for r in rows], "muted": is_muted()})


def get_group_aes_key():
    """从 settings 表读取群共享 AES 密钥；不存在则自动生成并写入数据库。"""
    db = get_db()
    row = db.execute(
        "SELECT value FROM settings WHERE key='group_aes_key'"
    ).fetchone()
    if row and row["value"]:
        return row["value"]
    key = base64.b64encode(secrets.token_bytes(32)).decode()
    db.execute(
        "INSERT OR REPLACE INTO settings (key, value) VALUES ('group_aes_key', ?)",
        (key,)
    )
    db.commit()
    return key


@app.route("/api/group-key", methods=["GET"])
@approved_required
def api_group_key():
    """已审核用户拉取群共享 AES 加密密钥。"""
    return jsonify({"ok": True, "key": get_group_aes_key()})


@app.route("/api/send", methods=["POST"])
@approved_required
def api_send():
    """普通已审核用户发送 AES-256-GCM 加密消息正文。

    客户端用群共享密钥加密明文生成 payload（iv + cipher），
    服务端只存储密文，无法读取内容。
    """
    if is_muted():
        return jsonify({"ok": False, "error": "当前群聊已开启全员禁言"})
    user = current_user()
    # 兼容客户端用表单或 JSON 发送
    if request.is_json:
        data = request.get_json(silent=True) or {}
        payload_raw = data.get("payload", "")
    else:
        payload_raw = request.form.get("payload", "")

    if not payload_raw:
        return jsonify({"ok": False, "error": "消息内容不能为空"})

    try:
        payload_obj = json.loads(payload_raw)
        payload = json.dumps(payload_obj)
    except Exception as e:
        return jsonify({"ok": False, "error": f"消息加密格式错误: {e}"})

    # 服务端侧对明文做 XSS 转义已无意义，因为 content 字段不再存放明文。
    # 解密后的明文渲染仍由客户端负责。
    db = get_db()
    cursor = db.cursor()
    cursor.execute(
        "INSERT INTO messages (user_id, username, content, payload) VALUES (?, ?, ?, ?)",
        (user["id"], user["username"], "", payload),
    )
    msg_id = cursor.lastrowid
    row = db.execute(
        """
        SELECT m.id, m.user_id, m.username, m.content, m.payload,
               strftime('%Y-%m-%d %H:%M:%S', m.created_at) AS created_at,
               u.avatar
        FROM messages m
        LEFT JOIN users u ON u.id = m.user_id
        WHERE m.id = ?
        """, (msg_id,)
    ).fetchone()
    db.commit()
    return jsonify({"ok": True, "message": dict(row)})


@app.route("/api/admin/mute", methods=["POST"])
@admin_required
def api_admin_mute():
    """开启全员禁言。"""
    db = get_db()
    db.execute("INSERT OR REPLACE INTO settings (key, value) VALUES ('mute_all', '1')")
    db.commit()
    return jsonify({"ok": True})


@app.route("/api/admin/unmute", methods=["POST"])
@admin_required
def api_admin_unmute():
    """解除全员禁言。"""
    db = get_db()
    db.execute("INSERT OR REPLACE INTO settings (key, value) VALUES ('mute_all', '0')")
    db.commit()
    return jsonify({"ok": True})


@app.route("/api/admin/approve/<int:user_id>", methods=["POST"])
@admin_required
def api_admin_approve(user_id):
    """管理员通过用户审核。"""
    db = get_db()
    db.execute("UPDATE users SET status = 'approved' WHERE id = ? AND role = 'user'", (user_id,))
    db.commit()
    return jsonify({"ok": True})


@app.route("/api/admin/reject/<int:user_id>", methods=["POST"])
@admin_required
def api_admin_reject(user_id):
    """管理员冻结用户（改回 pending）。"""
    db = get_db()
    db.execute("UPDATE users SET status = 'pending' WHERE id = ? AND role = 'user'", (user_id,))
    db.commit()
    return jsonify({"ok": True})


@app.route("/api/admin/delete/<int:user_id>", methods=["POST"])
@admin_required
def api_admin_delete(user_id):
    """管理员删除用户账号及其历史消息。"""
    db = get_db()
    db.execute("DELETE FROM messages WHERE user_id = ?", (user_id,))
    db.execute("DELETE FROM users WHERE id = ? AND role = 'user'", (user_id,))
    db.commit()
    return jsonify({"ok": True})


@app.route("/api/admin/delete-message/<int:msg_id>", methods=["POST"])
@admin_required
def api_admin_delete_message(msg_id):
    """管理员删除单条消息。"""
    db = get_db()
    db.execute("DELETE FROM messages WHERE id = ?", (msg_id,))
    db.commit()
    return jsonify({"ok": True})


@app.route("/api/admin/reset-password/<int:user_id>", methods=["POST"])
@admin_required
def api_admin_reset_password(user_id):
    """管理员重置指定用户密码。"""
    new_password = request.form.get("new_password", "").strip()
    if len(new_password) < 6:
        return jsonify({"ok": False, "error": "密码至少 6 位"})

    db = get_db()
    db.execute(
        "UPDATE users SET password_hash = ? WHERE id = ? AND role = 'user'",
        (generate_password_hash(new_password), user_id),
    )
    db.commit()
    return jsonify({"ok": True})


@app.route("/api/admin/change-username/<int:user_id>", methods=["POST"])
@admin_required
def api_admin_change_username(user_id):
    """管理员修改指定用户的用户名。"""
    new_username = request.form.get("new_username", "").strip()
    if not new_username:
        return jsonify({"ok": False, "error": "用户名不能为空"})

    db = get_db()
    existing = db.execute(
        "SELECT id FROM users WHERE username = ? AND id != ?", (new_username, user_id)
    ).fetchone()
    if existing:
        return jsonify({"ok": False, "error": "该用户名已被占用"})

    db.execute("UPDATE users SET username = ? WHERE id = ? AND role = 'user'", (new_username, user_id))
    db.execute("UPDATE messages SET username = ? WHERE user_id = ?", (new_username, user_id))
    db.commit()
    return jsonify({"ok": True})


@app.route("/api/health", methods=["GET"])
def api_health():
    """健康检查：客户端轮询服务器是否在线。"""
    return jsonify({"ok": True})


@app.route("/api/group-key", methods=["GET"])
@approved_required
def api_group_key():
    """已审核用户拉取群共享 AES 加密密钥。"""
    return jsonify({"ok": True, "key": get_group_aes_key()})


# 启动入口 ================================================
if __name__ == "__main__":
    init_db()
    cleanup_old_messages()
    schedule_cleanup()
    print("数据库已初始化")
    print("访问地址：http://127.0.0.1:5000")
    app.run(host="0.0.0.0", port=5000, debug=True, use_reloader=False)
