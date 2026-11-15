// Hero demo: walks through the real flow (request → decline window → ring → found) on fast-forward.
(() => {
  const stage = document.querySelector(".stage");
  const btn = document.getElementById("ringBtn");
  const label = document.getElementById("ringLabel");
  const status = document.getElementById("mockStatus");
  const cushion = document.getElementById("cushion");
  if (!stage || !btn) return;

  let timers = [];
  let state = "idle";
  const later = (ms, fn) => timers.push(setTimeout(fn, ms));
  const clear = () => { timers.forEach(clearTimeout); timers = []; };
  const set = (s, text, btnText) => {
    state = s;
    stage.classList.toggle("is-busy", s === "sent" || s === "countdown" || s === "ringing");
    stage.classList.toggle("is-ringing", s === "ringing");
    stage.classList.toggle("is-found", s === "found");
    status.textContent = text;
    label.textContent = btnText;
  };

  function reset() { clear(); set("idle", "눌러 보세요", "울리기"); }

  function start() {
    clear();
    set("sent", "요청을 보냈어요 · 응답 기다리는 중…", "취소");
    later(700, () => {
      let n = 3;
      set("countdown", `아내 폰에 요청이 떴어요. ${n}초 뒤 울려요 (데모는 빨리 감기)`, "취소");
      const tick = () => {
        n -= 1;
        if (n > 0) { status.textContent = `아내 폰에 요청이 떴어요. ${n}초 뒤 울려요 (데모는 빨리 감기)`; later(800, tick); }
        else set("ringing", "울리는 중이에요 🔔 쿠션을 눌러 찾아보세요", "그만 울리기");
      };
      later(800, tick);
    });
  }

  function found() {
    clear();
    set("found", "찾았대요! 🎉 소리 설정은 원래대로 돌아갔어요", "다시 해 보기");
  }

  btn.addEventListener("click", () => {
    if (state === "idle") start();
    else if (state === "found") reset();
    else { clear(); set("idle", state === "ringing" ? "울림을 멈췄어요" : "요청을 취소했어요", "울리기"); }
  });
  cushion.style.cursor = "pointer";
  cushion.addEventListener("click", () => { if (state === "ringing") found(); });
})();
