window.onload = () => {
  const container = document.getElementById('container');
  const errorText = document.getElementById('errorText');

  const statusContainer = document.getElementById("status-container");
  const statusText = document.getElementById('statusText');
  const statusSpinner = document.getElementById("status-spinner");

  const accessKeyInput = document.getElementById('accessKey');
  const btnInit = document.getElementById('btnInit');

  const cardContainer = document.getElementById('cardContainer');
  const btnCancel = document.getElementById('btnCancel');
  const volumeMeter = document.getElementById('volumeMeter');
  const bar1 = document.getElementById('bar1');
  const bar2 = document.getElementById('bar2');
  const bar3 = document.getElementById('bar3');

  function setStatusText(text) {
    statusContainer.style.display = 'flex';
    statusText.innerText = text;
    statusText.classList.remove("hidden");
  }

  function clearStatus() {
    statusContainer.style.display = 'none';
    statusText.innerText = '';
    statusText.classList.add('hidden');
  }

  function setErrorText(message) {
    errorText.innerText = message;
    errorText.classList.remove('hidden');
  }

  function clearError() {
    errorText.innerText = '';
    errorText.classList.add('hidden');
  }

  if (typeof Picovoice === 'undefined') {
    setErrorText('You must run `yarn build` before running `yarn start`');
    btnInit.disabled = true;
    return;
  }

  const cards = {};
  let micActive = true;
  let firstStart = true;

  function createCard(id, title, alternate) {
    if (!cards.hasOwnProperty(id)) {
      const root = document.createElement('div');
      const card = document.createElement('div');
      const titleArea = document.createElement('div');
      const valueArea = document.createElement('div');

      const lhsDiv = document.createElement('div');
      lhsDiv.innerText = title;

      titleArea.className = "title";
      titleArea.style.display = "flex";
      titleArea.style.width = "100%";
      titleArea.appendChild(lhsDiv);

      valueArea.innerText = "-";

      card.classList.add("card");
      card.appendChild(titleArea);
      card.appendChild(valueArea);

      root.classList.add("cardParent");
      root.appendChild(card);

      var alternateElement = undefined;
      if (alternate != null) {
        alternateElement = document.createElement('span');
        alternateElement.className = "card-alternate";
        alternateElement.innerText = `or ${alternate}`;

        root.appendChild(alternateElement);
      }

      cardContainer.appendChild(root);

      cards[id] = {
        card: card,
        alternate: alternateElement,
        title: titleArea,
        value: valueArea
      };
    } else {
      cards[id].title.innerText = title;
      cards[id].value.innerText = "-";
    }
  }

  function setActiveCard(id) {
    for (let key in cards) {
      cards[key].card.classList.remove("activeCard");
    }

    if (id !== null) {
      cards[id].card.classList.add("activeCard");
      cards[id].card.focus();
      cards[id].card.scrollIntoView({
        behavior: 'smooth',
        block: 'end'
      });
    }
  }

  function setCompletedCard(id, isAlternate) {
    if (id !== null) {
      if (isAlternate) {
        cards[id].card.classList.remove("activeCard");
        cards[id].alternate.classList.add("completedCard");
      } else {
        cards[id].card.classList.remove("activeCard");
        cards[id].card.classList.add("completedCard");
      }
    }
  }

  function setCardValue(id, value) {
    cards[id].value.innerText = value;
    cards[id].card.focus();
    cards[id].card.scrollIntoView({
      behavior: 'smooth',
      block: 'end'
    });
  }

  var screenString = "init";

  async function goToInitScreen() {
    screenString = "init";

    container.style.opacity = '0';
    await Picovoice.sleep(400);

    container.style.opacity = '1';

    for (let key in cards) {
      cards[key].card.classList.remove("activeCard");
      cards[key].card.classList.remove("completedCard");
      cards[key].value.innerText = "-";

      if (cards[key].alternate) {
        cards[key].alternate.classList.remove("completedCard");
      }
    }
    cardContainer.scrollTo({
      top: 0,
      behavior: "instant",
    });

    setStatusText("Ready to Start");
    statusContainer.style.display = 'block';

    btnInit.classList.remove('hidden');
    cardContainer.classList.add('hidden');
    btnCancel.classList.add('hidden');
    volumeMeter.classList.add('hidden');
  }

  function currentScreen() {
    return screenString;
  }

  function onVolume(volume) {
    if (!micActive) {
      volume = 0;
    }

    const baseHeight = 8;
    bar1.style.height = `${baseHeight + volume * 20}px`;
    bar2.style.height = `${baseHeight + volume * 32}px`;
    bar3.style.height = `${baseHeight + volume * 24}px`;
  }

  const callbacks = {
    setLoadingState: async (enabled) => {
      if (enabled) {
        statusSpinner.style.display = "inline";
        statusSpinner.style.opacity = "1";
      } else {
        statusSpinner.style.opacity = "0";
        
        await Picovoice.sleep(200);
        
        statusSpinner.style.display = "none";
      }
    },
    setStatusText,
    clearStatus,
    setErrorText,
    clearError,

    onVolume,
    onListening: (isListening) => {
      if (isListening) {
        bar1.style.backgroundColor = "var(--brand-primary)";
        bar2.style.backgroundColor = "var(--brand-primary)";
        bar3.style.backgroundColor = "var(--brand-primary)";
      } else {
        bar1.style.backgroundColor = "#888";
        bar2.style.backgroundColor = "#888";
        bar3.style.backgroundColor = "#888";
      }
      micActive = isListening;
    },

    createCard,
    setActiveCard,
    setCompletedCard,
    setCardValue,
    goToInitScreen,
    currentScreen,
  };

  btnInit.onclick = async () => {
    clearError();
    const accessKey = accessKeyInput.value.trim();
    if (!accessKey) {
      setErrorText('Please enter an AccessKey');
      return;
    }

    btnInit.disabled = true;

    try {
      if (firstStart) {
        await Picovoice.init(accessKey, callbacks);
        firstStart = false;
      }

      screenString = "demo";

      container.style.opacity = '0';
      await Picovoice.sleep(400);

      accessKeyInput.classList.add('hidden');
      btnInit.classList.add('hidden');
      cardContainer.classList.remove('hidden');
      btnCancel.classList.remove('hidden');
      volumeMeter.classList.remove('hidden');

      container.style.opacity = '1';

      await Picovoice.start();
    } catch (e) {
      setErrorText(e.toString());
    } finally {
      btnInit.disabled = false;
    }
  };

  btnCancel.onclick = async () => {
    btnCancel.disabled = true;

    try {
      await Picovoice.stop();
      await Picovoice.sleep(400);
    } catch (e) {
      setErrorText(e.toString());
    } finally {
      btnCancel.disabled = false;
    }
  };
};

window.onbeforeunload = () => {
  Picovoice.release();
};