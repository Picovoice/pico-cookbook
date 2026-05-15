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
  let bubbleDotInterval = null;

  const statusContainer = document.getElementById("status-container");
  const status = document.getElementById("status");
  const errorContainer = document.getElementById("error-container");
  const error = document.getElementById("error");
  const dotdotdot = document.getElementById("dotdotdot");


  const loadContainerParent = document.getElementById("load-container-parent");
  const loadContainer = document.getElementById("load-container");
  const loadButton = document.getElementById("load-button");
  const loadButtonTooltip = document.getElementById("load-button-tooltip");

  const mainContainerParent = document.getElementById("main-container-parent");
  const mainContainer = document.getElementById("main-container");

  const chatBlock = document.getElementById("chat-block");
  const volumeMeterCaller = document.getElementById('volume-meter-caller');
  const bar1 = document.getElementById('bar1');
  const bar2 = document.getElementById('bar2');
  const bar3 = document.getElementById('bar3');
  const aiState = document.getElementById("ai-state");

  const accessKey = document.getElementById("access-key");
  const name = document.getElementById("name");
  const initButton = document.getElementById("init-button");
  const initButtonTooltip = document.getElementById("init-button-tooltip");

  const hudContainer = document.getElementById("hud-container");
  const hudOptions = document.getElementById("hud-options");
  const volumeMeterUser = document.getElementById('volume-meter-user');
  const bar4 = document.getElementById('bar4');
  const bar5 = document.getElementById('bar5');
  const bar6 = document.getElementById('bar6');

  const writeError = (errorString) => {
    errorContainer.style.display = 'block';
    error.innerText = `Error: ${errorString}`;
  };
  const clearError = () => {
    errorContainer.style.display = 'none';
    error.innerText = "";
  };

  const writeStatus = (statusString) => {
    statusContainer.style.display = 'block';
    status.innerText = statusString;
  };

  const startDot = () => {
    if (dotInterval) {
      clearInterval(dotInterval);
    }
    dotInterval = setInterval(() => {
      dotIdx = (dotIdx + 1) % ANIMATION_FRAMES.length;
      dotdotdot.innerText = ANIMATION_FRAMES[dotIdx];
    }, 200);
  }
  const stopDot = () => {
    if (dotInterval) {
      clearInterval(dotInterval);
    }
    dotIdx = 0;
    dotdotdot.innerText = '';
  }

  const onVolumeCallback = volume => {
    const baseHeight = 8;
    bar1.style.height = `${baseHeight + volume * 20}px`;
    bar2.style.height = `${baseHeight + volume * 32}px`;
    bar3.style.height = `${baseHeight + volume * 24}px`;

    bar4.style.height = `${baseHeight + volume * 20}px`;
    bar5.style.height = `${baseHeight + volume * 32}px`;
    bar6.style.height = `${baseHeight + volume * 24}px`;
  };

  const enableLoadButton = _ => {
    if (accessKey.value.length > 0) {
      loadButton.removeAttribute("disabled");
      loadButton.setAttribute("coloured", "");
      loadButtonTooltip.innerHTML = "";
    } else {
      loadButton.setAttribute("disabled", "");
      loadButton.removeAttribute("coloured");
      loadButtonTooltip.innerHTML = "fill in access key";
    }
  }
  accessKey.addEventListener("input", enableLoadButton);

  const enableStartButton = _ => {
    if (sanitizeForOrca(name.value).replaceAll(" ", "").length > 0) {
      initButton.removeAttribute("disabled");
      initButton.setAttribute("coloured", "");
      initButtonTooltip.innerHTML = "";
    } else {
      initButton.setAttribute("disabled", "");
      initButton.removeAttribute("coloured");
      initButtonTooltip.innerHTML = "fill in name";
    }
  }
  name.addEventListener("input", enableStartButton);

  let hudFadeoutTimeoutHandle = null;
  let hudFadeoutEndStateFunction = null;

  const restartDemo = async () => {
    chatBlock.style.opacity = "0";
    await Picovoice.sleep(400);
    
    chatBlock.replaceChildren();
    chatBlock.style.opacity = "1";

    if (hudFadeoutTimeoutHandle && hudFadeoutEndStateFunction) {
      clearTimeout(hudFadeoutTimeoutHandle);
      hudFadeoutEndStateFunction();
    }

    initButton.disabled = false;
    initButton.removeAttribute("disabled");
    initButton.setAttribute("coloured", "");

    name.disabled = false;
  };

  let currentBubbleText = "";
  let currentBubbleDots = "";

  const startBubbleDot = () => {
    if (bubbleDotInterval) {
      clearInterval(bubbleDotInterval);
    }
    bubbleDotInterval = setInterval(() => {
      dotIdx = (dotIdx + 1) % ANIMATION_FRAMES.length;
      currentBubbleDots = ANIMATION_FRAMES[dotIdx];

      chatBlock.lastElementChild.innerHTML = currentBubbleText + currentBubbleDots;
    }, 200);
  }
  const stopBubbleDot = () => {
    if (bubbleDotInterval) {
      clearInterval(bubbleDotInterval);
    }
    dotIdx = 0;
    currentBubbleDots = '';
  
    chatBlock.lastElementChild.innerHTML = currentBubbleText;
  }

  const sendMessage = (message, obj) => {
    if (message === "SET_STATUS") {
      let text = obj;
      writeStatus(text);

    } else if (message === "ADD_TO_BUBBLE") {
      let text = obj;
      if (text.length === 0)
        return;

      currentBubbleText += text;
      chatBlock.lastElementChild.innerHTML = currentBubbleText + currentBubbleDots;
      if (chatBlock.lastElementChild.innerHTML.length > 0) {
        chatBlock.lastElementChild.style.opacity = "1";
      }

    } else if (message === "NEW_CALLER_BUBBLE") {
      let text = obj;
      let bubble = document.createElement("div");
      bubble.classList.add("caller-bubble");
      bubble.style.opacity = "0";
      bubble.innerHTML += text;
      if (bubble.innerHTML.length > 0) {
        bubble.style.opacity = "1";
      }

      currentBubbleText = text;
      chatBlock.appendChild(bubble);

    } else if (message === "NEW_AI_BUBBLE") {
      let text = obj;
      let bubble = document.createElement("div");
      bubble.classList.add("ai-bubble");
      bubble.style.opacity = "0";
      bubble.innerHTML += text;
      if (bubble.innerHTML.length > 0) {
        bubble.style.opacity = "1";
      }

      currentBubbleText = text;
      chatBlock.appendChild(bubble);

    } else if (message === "START_LISTENING") {
      volumeMeterCaller.style.opacity = "1";
      startBubbleDot();

    } else if (message === "STOP_LISTENING") {
      volumeMeterCaller.style.opacity = "0";
      stopBubbleDot();

    } else if (message === "GIVE_USER_OPTIONS") {
      volumeMeterUser.style.opacity = "1";
      let text = obj;

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

    } else if (message === "SELECT_OPTION") {
      volumeMeterUser.style.opacity = "0";
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

    } else if (message === "SET_AI_STATE") {
      let text = obj;
      aiState.innerHTML = text;

    } else if (message === "RESTART_DEMO") {
      restartDemo();
    }
  };

  const makeRequest = (message) => {
    if (message === "BUBBLE_LENGTH") {
      return chatBlock.lastElementChild.innerHTML.length;
    }
  };

  if (typeof Picovoice === 'undefined') {
    writeError("You must run `yarn build` before running yarn start");
    loadButtonTooltip.innerHTML = "fix error first";
    return;
  }

  let startFunction = null;

  loadButton.addEventListener("click", async () => {
    loadButton.disabled = true;
    loadButton.setAttribute("disabled", "");
    loadButton.removeAttribute("coloured");

    writeStatus("Loading");
    startDot();

    try {
      startFunction = await Picovoice.init(
        accessKey.value,
        sanitizeForOrca(name.value),
        sendMessage,
        makeRequest,
        onVolumeCallback,
      );

    } catch (e) {
      writeError(e.message);
      return;
    } finally {
      stopDot();
    }

    clearError();

    if (startFunction === null) {
      writeStatus("Loading failed");
      return;
    } else {
      writeStatus("");
    }

    loadContainer.style.opacity = "0";
    mainContainer.style.opacity = "1";
    mainContainerParent.style.visibility = "visible";

    await Picovoice.sleep(400);

    loadContainerParent.style.visibility = "hidden";
  });

  initButton.addEventListener("click", async () => {
    initButton.disabled = true;
    initButton.setAttribute("disabled", "");
    initButton.removeAttribute("coloured");

    name.disabled = true;

    let success = await Picovoice.updateStartParameters(sanitizeForOrca(name.value));
    if (!success) {
      console.log("failed to update start parameters");
    }

    try {
      await startFunction();
    } catch (e) {
      writeError(e.message);
    }
  });
};
