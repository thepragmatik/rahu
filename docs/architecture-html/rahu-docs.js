
/* Rahu docs — progressive enhancement only. Every page is fully readable with
   JavaScript disabled; this layer adds isolation, section links and filtering. */
(function(){
  "use strict";

  /* 1. Layer stack: click a layer to inspect it in place. */
  document.querySelectorAll(".stack").forEach(function(stack){
    var detail = stack.querySelector(".detail");
    if(!detail) return;
    var layers = stack.querySelectorAll(".layer");
    layers.forEach(function(btn){
      btn.setAttribute("aria-pressed","false");
      btn.addEventListener("click", function(){
        var already = btn.getAttribute("aria-pressed")==="true";
        layers.forEach(function(o){
          o.setAttribute("aria-pressed","false");
          o.hidden = false;
        });
        if(already){ detail.innerHTML=""; detail.classList.remove("detail"); return; }
        layers.forEach(function(o){ if(o!==btn) o.hidden = true; });
        btn.setAttribute("aria-pressed","true");
        detail.innerHTML = btn.dataset.detail || "<p>No detail recorded for this layer.</p>";
      });
    });
  });

  /* 2. Segmented control: show one panel, hide the others. */
  document.querySelectorAll(".seg[data-target]").forEach(function(seg){
    var btns = seg.querySelectorAll("button[data-panel]");
    function show(name){
      btns.forEach(function(b){
        var on = b.dataset.panel===name;
        b.setAttribute("aria-pressed", on?"true":"false");
        var el = document.getElementById(name);
        if(el) el.classList.toggle("hidden", !on);
      });
    }
    btns.forEach(function(b){
      b.addEventListener("click", function(){ show(b.dataset.panel); });
    });
    var first = seg.querySelector("button[data-panel]");
    if(first) show(first.dataset.panel);
  });

  /* 3. Findings filter: severity chips narrow the table rows. */
  document.querySelectorAll("[data-filter-group]").forEach(function(group){
    var rows = group.querySelectorAll("[data-sev]");
    var chips = group.querySelectorAll("[data-filter]");
    function apply(f){
      var shown = 0;
      rows.forEach(function(r){
        var on = (f==="all") || r.dataset.sev.split(" ").indexOf(f)>-1;
        r.classList.toggle("hidden", !on);
        if(on) shown++;
      });
      var live = document.getElementById(group.dataset.filterGroup);
      if(live) live.textContent = shown + " finding" + (shown===1?"":"s") + " shown";
    }
    chips.forEach(function(chip){
      chip.addEventListener("click", function(){
        chips.forEach(function(c){ c.setAttribute("aria-pressed","false"); });
        chip.setAttribute("aria-pressed","true");
        apply(chip.dataset.filter);
      });
    });
    apply("all");
  });

  /* 4. Scroll-spy: highlight the nav entry for the section in view. */
  var links = Array.prototype.slice.call(document.querySelectorAll("nav.toc a"));
  var secs = links.map(function(a){ return document.querySelector(a.getAttribute("href")); })
                 .filter(Boolean);
  if("IntersectionObserver" in window && secs.length){
    var seen = {};
    var io = new IntersectionObserver(function(entries){
      entries.forEach(function(en){
        seen[en.target.id] = en.isIntersecting ? en.intersectionRatio : 0;
      });
      var best = null, bestV = -1;
      Object.keys(seen).forEach(function(id){
        if(seen[id] > bestV){ bestV = seen[id]; best = id; }
      });
      links.forEach(function(a){
        a.setAttribute("aria-current",
          a.getAttribute("href")==="#"+best ? "true":"false");
      });
    },{rootMargin:"-15% 0px -60% 0px",threshold:[0,.15,.4,.8]});
    secs.forEach(function(s){ io.observe(s); });
  }

  /* 5. Copy-to-clipboard on code blocks, with a real fallback for file:// use. */
  document.querySelectorAll("pre[data-copy]").forEach(function(pre){
    var btn = document.createElement("button");
    btn.type="button"; btn.textContent="Copy"; btn.className="copybtn";
    btn.setAttribute("aria-label","Copy code to clipboard");
    btn.addEventListener("click", function(){
      var text = pre.innerText;
      function done(ok){
        btn.textContent = ok ? "Copied" : "Press ⌘C";
        setTimeout(function(){ btn.textContent="Copy"; }, 1600);
      }
      if(navigator.clipboard && navigator.clipboard.writeText){
        navigator.clipboard.writeText(text).then(function(){done(true);},
                                               function(){done(false);});
      } else { done(false); }
    });
    pre.style.position="relative";
    pre.appendChild(btn);
  });

  /* 6. Deep-link helper: copy the current section anchor without a permalink bar. */
  document.querySelectorAll("h2[id], h3[id]").forEach(function(h){
    if(h.querySelector(".anchor")) return;
    var a = document.createElement("a");
    a.className="anchor"; a.href="#"+h.id; a.textContent="#";
    a.setAttribute("aria-label","Link to this section");
    h.appendChild(a);
  });
})();
