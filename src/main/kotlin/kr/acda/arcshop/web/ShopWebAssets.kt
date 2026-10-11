package kr.acda.arcshop.web

import java.nio.file.Files
import java.nio.file.Path

/**
 * MCEF 브라우저 상점 UI의 정적 자산(공용 CSS/JS)과, 플레이어별로 서명 URL을 채워 넣은 HTML 셸을
 * 만드는 곳. `style.css`/`app.js`는 모든 플레이어가 공유하는 고정 파일이라 `onEnable`마다 한 번만
 * (덮어)쓰면 되고, `index.html`은 플레이어마다 서로 다른 서명(pid/exp/sig)이 박혀야 하므로
 * [ShopWebController]가 상점을 열 때마다 그 플레이어 전용 파일로 새로 만든다.
 */
object ShopWebAssets {

	fun writeSharedAssets(dir: Path) {
		Files.createDirectories(dir)
		Files.writeString(dir.resolve("style.css"), STYLE_CSS)
		Files.writeString(dir.resolve("app.js"), APP_JS)
	}

	fun renderIndexHtml(cssUrl: String, jsUrl: String, actionUrl: String): String = """
		<!doctype html>
		<html lang="ko">
		<head>
		<meta charset="utf-8">
		<meta name="viewport" content="width=device-width, initial-scale=1">
		<title>ARCShop</title>
		<link rel="stylesheet" href="${escapeAttr(cssUrl)}">
		</head>
		<body>
		<div id="app">
		  <aside id="sidebar">
		    <h1>ARCShop</h1>
		    <nav id="categories"></nav>
		  </aside>
		  <main>
		    <h2 id="categoryTitle"></h2>
		    <div id="message" class="message"></div>
		    <div id="products"></div>
		  </main>
		</div>
		<script>window.ARCSHOP_ACTION_URL = ${jsStringLiteral(actionUrl)};</script>
		<script src="${escapeAttr(jsUrl)}"></script>
		</body>
		</html>
	""".trimIndent()

	// Query-string values here are HMAC signatures (URL-safe base64), UUIDs, and epoch-ms
	// integers — none of these can contain '&' or '"', but we escape defensively anyway rather
	// than assume that never changes.
	private fun escapeAttr(s: String): String = s.replace("&", "&amp;").replace("\"", "&quot;")

	private fun jsStringLiteral(s: String): String {
		val sb = StringBuilder("'")
		for (c in s) {
			when (c) {
				'\\' -> sb.append("\\\\")
				'\'' -> sb.append("\\'")
				'\n' -> sb.append("\\n")
				'\r' -> sb.append("\\r")
				'<' -> sb.append("\\x3c")
				else -> sb.append(c)
			}
		}
		sb.append("'")
		return sb.toString()
	}

	private val STYLE_CSS = """
		* { box-sizing: border-box; }
		html, body {
		  margin: 0; padding: 0; height: 100%;
		  background: #1b1b1f; color: #e8e8ec;
		  font-family: "Segoe UI", "Malgun Gothic", sans-serif;
		}
		#app { display: flex; height: 100%; }
		#sidebar {
		  width: 220px; background: #232329; padding: 16px;
		  overflow-y: auto; border-right: 1px solid #35353d; flex-shrink: 0;
		}
		#sidebar h1 { font-size: 18px; margin: 0 0 16px; color: #f5d060; }
		.category-btn {
		  display: block; width: 100%; text-align: left; padding: 10px 12px; margin-bottom: 6px;
		  background: #2c2c34; color: #e8e8ec; border: none; border-radius: 6px;
		  cursor: pointer; font-size: 14px;
		}
		.category-btn:hover { background: #3a3a44; }
		.category-btn.active { background: #f5d060; color: #1b1b1f; font-weight: 600; }
		main { flex: 1; padding: 20px 28px; overflow-y: auto; }
		main h2 { margin-top: 0; color: #f5d060; }
		.message { min-height: 20px; margin-bottom: 12px; font-size: 14px; }
		.message.ok { color: #7ee787; }
		.message.error { color: #ff7b72; }
		#products { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 14px; }
		.product-card {
		  background: #232329; border: 1px solid #35353d; border-radius: 10px; padding: 14px;
		  display: flex; flex-direction: column; gap: 8px;
		}
		.product-title { font-size: 15px; font-weight: 600; }
		.product-price { font-size: 13px; color: #f5d060; }
		.product-meta { font-size: 12px; color: #9a9aa5; }
		.product-actions { display: flex; flex-wrap: wrap; gap: 6px; margin-top: auto; }
		.action-btn {
		  flex: 1 1 auto; padding: 8px 10px; border: none; border-radius: 6px;
		  cursor: pointer; font-size: 13px; color: #1b1b1f;
		}
		.action-btn.buy { background: #7ee787; }
		.action-btn.sell { background: #ffb86b; }
		.action-btn:disabled { opacity: 0.5; cursor: default; }
		.action-btn:hover:not(:disabled) { filter: brightness(1.08); }
	""".trimIndent()

	private val APP_JS = """
		(function () {
		  var state = { data: null, activeCategory: null };
		  var actionUrl = window.ARCSHOP_ACTION_URL;

		  function call(params) {
		    return fetch(actionUrl + '&' + params).then(function (r) { return r.json(); });
		  }

		  function stripColor(s) {
		    return (s || '').replace(/[&§][0-9A-Fa-fK-Ok-oRr]/g, '');
		  }

		  function format(n) {
		    return Number(n).toLocaleString('ko-KR', { maximumFractionDigits: 2 });
		  }

		  function priceLabel(p) {
		    if (p.type === 'economy') return format(p.amount) + ' 골드';
		    if (p.type === 'cash') return format(p.amount) + ' 캐시';
		    if (p.type === 'item') return p.label + ' x' + p.amount;
		    return '';
		  }

		  function render(data) {
		    state.data = data;
		    var stillValid = data.categories.some(function (c) { return c.id === state.activeCategory; });
		    if (!state.activeCategory || !stillValid) {
		      state.activeCategory = data.categories.length ? data.categories[0].id : null;
		    }
		    renderSidebar();
		    renderProducts();
		  }

		  function renderSidebar() {
		    var nav = document.getElementById('categories');
		    nav.innerHTML = '';
		    state.data.categories.forEach(function (cat) {
		      var btn = document.createElement('button');
		      btn.className = 'category-btn' + (cat.id === state.activeCategory ? ' active' : '');
		      btn.textContent = stripColor(cat.displayName);
		      btn.onclick = function () {
		        state.activeCategory = cat.id;
		        renderSidebar();
		        renderProducts();
		      };
		      nav.appendChild(btn);
		    });
		  }

		  function renderProducts() {
		    var grid = document.getElementById('products');
		    grid.innerHTML = '';
		    var cat = state.data.categories.find(function (c) { return c.id === state.activeCategory; });
		    if (!cat) {
		      document.getElementById('categoryTitle').textContent = '';
		      return;
		    }
		    document.getElementById('categoryTitle').textContent = stripColor(cat.displayName);
		    cat.products.forEach(function (p) { grid.appendChild(productCard(p)); });
		  }

		  function productCard(p) {
		    var card = document.createElement('div');
		    card.className = 'product-card';

		    var title = document.createElement('div');
		    title.className = 'product-title';
		    title.textContent = stripColor(p.displayName);
		    card.appendChild(title);

		    var price = document.createElement('div');
		    price.className = 'product-price';
		    price.textContent = p.price.map(priceLabel).join(' + ');
		    card.appendChild(price);

		    var meta = document.createElement('div');
		    meta.className = 'product-meta';
		    var metaText = '보유: ' + p.owned;
		    if (p.personalLimit !== null && p.personalLimit !== undefined) {
		      metaText += ' · 구매 한도: ' + p.purchased + ' / ' + p.personalLimit;
		    }
		    meta.textContent = metaText;
		    card.appendChild(meta);

		    var actions = document.createElement('div');
		    actions.className = 'product-actions';
		    if (p.buyable) {
		      actions.appendChild(actionButton('구매 1개', 'buy', p.id, 1));
		      actions.appendChild(actionButton('구매 64개', 'buy', p.id, 64));
		    }
		    if (p.sellable) {
		      actions.appendChild(actionButton('판매 1개', 'sell', p.id, 1));
		      if (p.owned > 0) actions.appendChild(actionButton('전체 판매(' + p.owned + ')', 'sell', p.id, p.owned));
		    }
		    card.appendChild(actions);
		    return card;
		  }

		  function actionButton(label, op, productId, qty) {
		    var btn = document.createElement('button');
		    btn.className = op === 'buy' ? 'action-btn buy' : 'action-btn sell';
		    btn.textContent = label;
		    btn.onclick = function () {
		      btn.disabled = true;
		      call('op=' + op + '&productId=' + encodeURIComponent(productId) + '&qty=' + qty)
		        .then(function (res) {
		          showMessage(res.message, res.ok);
		          if (res.categories) render(res);
		        })
		        .catch(function () { showMessage('요청 실패 — 다시 시도하세요.', false); })
		        .finally(function () { btn.disabled = false; });
		    };
		    return btn;
		  }

		  function showMessage(text, ok) {
		    var el = document.getElementById('message');
		    if (!text) { el.textContent = ''; el.className = 'message'; return; }
		    el.textContent = text;
		    el.className = ok ? 'message ok' : 'message error';
		  }

		  function refresh() {
		    call('op=state').then(function (res) {
		      if (res.categories) render(res);
		    });
		  }

		  refresh();
		})();
	""".trimIndent()
}
