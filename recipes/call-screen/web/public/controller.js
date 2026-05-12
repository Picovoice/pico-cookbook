const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

function sanitizeForOrca(str) {
  replacements = {
    "\n": " ",
    "\r": " ",
    "\t": " ",
    "“": '"',
    "”": '"',
    "‘": "'",
    "’": "'",
    "—": "-",
    "–": "-",
    "…": "...",
  };

  for (const [k,v] of Object.entries(replacements)) {
    str = str.replaceAll(k, v);
  }

  return str;
}

window.onload = () => {
  const ANIMATION_FRAMES = ["   ", ".  ", ".. ", "...", " ..", "  ."];

  let dotIdx = 0;
  let dotInterval = null;

  const statusContainer = document.getElementById("status-container");
  const status = document.getElementById("status");
  const errorContainer = document.getElementById("error-container");
  const error = document.getElementById("error");
  const dotdotdot = document.getElementById("dotdotdot");

  const chatBlock = document.getElementById("chat-block");
  const aiState = document.getElementById("ai-state");

  const accessKey = document.getElementById("accessKey");
  const name = document.getElementById("name");
  const initButton = document.getElementById("init");
  const initButtonTooltip = document.getElementById("initButtonTooltip");

  const hudContainer = document.getElementById("hud-container");
  const hudOptions = document.getElementById("hud-options");
  const hudTemp = document.getElementById("hud-temp");

  const writeError = (errorString) => {
    errorContainer.style.display = 'block';
    error.style.display = 'block';
    error.innerText = `Error: ${errorString}`;
    writeStatus("Error");
  };

  const writeStatus = (statusString) => {
    statusContainer.style.display = 'block';
    status.style.display = 'inline';
    status.innerText = statusString;
  };

  const enableInitButton = _ => {
    let isValid = (accessKey.value.length > 0) && (sanitizeForOrca(name.value).replaceAll(" ", "").length > 0);
    if (isValid) {
      initButton.removeAttribute("disabled");
      initButton.setAttribute("coloured", "");
      initButtonTooltip.innerHTML = "";
    } else {
      initButton.setAttribute("disabled", "");
      initButton.removeAttribute("coloured");
      initButtonTooltip.innerHTML = "fill in access key and name";
    }
  }

  accessKey.addEventListener("input", enableInitButton);
  name.addEventListener("input", enableInitButton);

  //let animationIndex = 0;
  //const animationElement = document.createElement("span");
  /*const animationInterval = setInterval(() => {
    const frame = ANIMATION_FRAMES[animationIndex];
    animationElement.innerText = ` ${frame} `;
    animationIndex = (animationIndex + 1) % ANIMATION_FRAMES.length;
  }, 500);
  const state = {
    bubbleElem: null,
    upperElem: null,
    lowerElem: null,
    upperTextElem: null,
    lowerTextElem: null,
  };*/

  const startDot = () => {
    if (dotInterval) {
      clearInterval(dotInterval);
    }
    dotInterval = setInterval(() => {
      dotIdx = (dotIdx + 1) % ANIMATION_FRAMES.length;
      dotdotdot.innerText = ANIMATION_FRAMES[dotIdx];
    }, 100);
  }

  const stopDot = () => {
    if (dotInterval) {
      clearInterval(dotInterval);
    }
    dotIdx = 0;
    dotdotdot.innerText = '';
  }

  let hudFadeoutTimeoutHandle = null;
  let hudFadeoutEndStateFunction = null;
  let hudTempTimeoutHandle = null;

  const restartDemo = async () => {
    chatBlock.style.opacity = "0";
    await sleep(400);
    
    chatBlock.replaceChildren();
    chatBlock.style.opacity = "1";

    if (hudFadeoutTimeoutHandle && hudFadeoutEndStateFunction) {
      clearTimeout(hudFadeoutTimeoutHandle);
      hudFadeoutEndStateFunction();
    }

    if (hudTempTimeoutHandle) {
      clearTimeout(hudTempTimeoutHandle);
      hudTemp.innerHTML = "";
    }

    initButton.disabled = false;
    initButton.removeAttribute("disabled");
    initButton.setAttribute("coloured", "");
  };

  const sendMessage = (message, obj) => {
    if (message === "status") {
      let text = obj;
      writeStatus(text);

    } else if (message === "add to bubble") {
      let text = obj;
      chatBlock.lastElementChild.innerHTML += text;
      if (chatBlock.lastElementChild.innerHTML.length > 0) {
        chatBlock.lastElementChild.style.opacity = "1";
      }

    } else if (message === "new caller bubble") {
      let text = obj;
      let bubble = document.createElement("div");
      bubble.classList.add("caller-bubble");
      bubble.style.opacity = "0";
      bubble.innerHTML += text;
      if (bubble.innerHTML.length > 0) {
        bubble.style.opacity = "1";
      }

      chatBlock.appendChild(bubble);

    } else if (message === "new ai bubble") {
      let text = obj;
      let bubble = document.createElement("div");
      bubble.classList.add("ai-bubble");
      bubble.style.opacity = "0";
      bubble.innerHTML += text;
      if (bubble.innerHTML.length > 0) {
        bubble.style.opacity = "1";
      }

      chatBlock.appendChild(bubble);

    } else if (message === "give user options") {
      let text = obj;

      hudTemp.innerHTML = "";

      hudOptions.replaceChildren();
      for (text of text.split(",")) {
        if (text.length === 0)
          continue;

        let option = document.createElement("div");
        option.innerHTML = text;
        option.id = "option-" + text.replace(" ", "_").toLowerCase();

        let tooltip = document.createElement("span");
        tooltip.classList.add("tooltip");
        tooltip.innerHTML = "To select this voice command, speak into your microphone"
        
        option.appendChild(tooltip);
        
        hudOptions.appendChild(option);
      }

      hudContainer.style.opacity = "1";

      // TODO: display a dotdotdot?
      
    } else if (message === "select option") {
      let text = obj;

      let element = document.getElementById("option-" + text.replace(" ", "_").toLowerCase());
      if (element) {
        element.setAttribute("coloured", "");

        hudFadeoutEndStateFunction = () => {
            hudContainer.style.opacity = "0";
            element.removeAttribute("coloured");
        };
        hudFadeoutTimeoutHandle = setTimeout(hudFadeoutEndStateFunction, 2000);
      }

    } else if (message === "unknown user option") {
      let timeoutMs = obj;
      hudTemp.innerHTML = "Unknown Action";

      if (hudTempTimeoutHandle != null) {
        clearTimeout(hudTempTimeoutHandle);
      }

      hudTempTimeoutHandle = setTimeout(
        () => { hudTemp.innerHTML = ""; },
        timeoutMs);

    } else if (message === "ai state") {
      let text = obj;
      aiState.innerHTML = text;

    } else if (message === "restart demo") {
      restartDemo();
    }
  };

  const makeRequest = (message) => {
    if (message === "bubble length") {
      return chatBlock.lastElementChild.innerHTML.length;
    }
  };

  initButtonTooltip.innerHTML = "fill in access key and name";

  if (typeof Picovoice === 'undefined') {
    writeError("You must run `yarn build` before running yarn start");
    initButtonTooltip.innerHTML = "fix error first";
    return;
  }

  initButton.addEventListener("click", async () => {
    initButton.disabled = true;
    initButton.setAttribute("disabled", "");
    initButton.removeAttribute("coloured");

    writeStatus("Loading");
    startDot();

    let start = null;
    try {
      start = await Picovoice.init(
        accessKey.value,
        sanitizeForOrca(name.value),
        sendMessage,
        makeRequest
      );

    } catch (e) {
      writeError(e.message);
    } finally {
      stopDot();
    }

    try {
      if (start !== null) {
        writeStatus("");
        await start();
      }
    } catch (e) {
      writeError(e.message);
    }
  });
};
