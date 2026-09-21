# 超小型文字群聊应用后端
# 技术栈：Flask + SQLite + 原生前端 AJAX 轮询
# 功能：仅文字群聊，QQ 号登录，管理员可全账号管理，支持全员禁言

import os
import sqlite3
import secrets
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
            created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
        )
    """)
    db.execute("""
        CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            username TEXT NOT NULL,
            content TEXT NOT NULL,
            created_at TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
        )
    """)
    db.execute("""
        CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        )
    """)
    # 默认不开启全员禁言
    db.execute("INSERT OR IGNORE INTO settings (key, value) VALUES ('mute_all', '0')")

    # 创建内置管理员账号，仅当不存在时插入
    admin_hash = generate_password_hash(ADMIN_PASSWORD)
    db.execute("""
        INSERT OR IGNORE INTO users (qq, username, password_hash, role, status)
        VALUES (?, ?, ?, 'admin', 'approved')
    """, ("admin@system", ADMIN_USERNAME, admin_hash))
    db.commit()
    db.close()


# 工具函数 =================================================
def current_user():
    """从 session 获取当前登录用户的信息，未登录返回 None。"""
    uid = session.get("user_id")
    if not uid:
        return None
    row = get_db().execute(
        "SELECT id, qq, username, role, status FROM users WHERE id = ?", (uid,)
    ).fetchone()
    return dict(row) if row else None


def is_muted():
    """查询当前是否开启全员禁言。"""
    row = get_db().execute(
        "SELECT value FROM settings WHERE key = 'mute_all'"
    ).fetchone()
    return row is not None and row["value"] == "1"


def admin_required(f):
    """装饰器：要求当前用户为已登录管理员。"""
    @wraps(f)
    def wrapped(*args, **kwargs):
        user = current_user()
        if not user or user["role"] != "admin":
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
        SELECT m.id, m.username, m.content,
               strftime('%Y-%m-%d %H:%M:%S', m.created_at) AS created_at
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


# API 接口 =================================================
@app.route("/api/messages")
def api_messages():
    """轮询接口：返回新消息，全员禁言状态同步返回。"""
    user = current_user()
    if not user:
        return jsonify({"ok": False, "error": "未登录"}), 401
    last_id = request.args.get("last_id", type=int, default=0)
    db = get_db()
    rows = db.execute(
        """
        SELECT id, username, content,
               strftime('%Y-%m-%d %H:%M:%S', created_at) AS created_at
        FROM messages
        WHERE id > ?
        ORDER BY id ASC
        """,
        (last_id,),
    ).fetchall()
    return jsonify({"messages": [dict(r) for r in rows], "muted": is_muted()})


@app.route("/api/send", methods=["POST"])
@approved_required
def api_send():
    """普通已审核用户发送文字消息。"""
    if is_muted():
        return jsonify({"ok": False, "error": "当前群聊已开启全员禁言"})
    user = current_user()
    data = request.get_json(silent=True) or {}
    content = data.get("content", "").strip()
    if not content:
        return jsonify({"ok": False, "error": "消息内容不能为空"})
    if len(content) > 500:
        return jsonify({"ok": False, "error": "消息长度不能超过 500 字符"})

    # 简单转义，防止 XSS
    content = content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    db = get_db()
    db.execute(
        "INSERT INTO messages (user_id, username, content) VALUES (?, ?, ?)",
        (user["id"], user["username"], content),
    )
    db.commit()
    return jsonify({"ok": True})


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


# 启动入口 ================================================
if __name__ == "__main__":
    init_db()
    print("数据库已初始化")
    print("访问地址：http://127.0.0.1:5000")
    app.run(host="0.0.0.0", port=5000, debug=True)
