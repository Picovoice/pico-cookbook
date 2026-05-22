window.onload = () => {
  const titleText = document.getElementById('titleText');
  const errorText = document.getElementById('errorText');
  const statusText = document.getElementById('statusText');
  const accessKeyInput = document.getElementById('accessKey');
  const btnInit = document.getElementById('btnInit');

  const cardContainer = document.getElementById('cardContainer');
  const btnCancel = document.getElementById('btnCancel');
  const volumeMeter = document.getElementById('volumeMeter');
  const bar1 = document.getElementById('bar1');
  const bar2 = document.getElementById('bar2');
  const bar3 = document.getElementById('bar3');

  function setStatusText(text) {
    statusText.innerText = text;
    statusText.classList.remove("hidden");
  }

  function clearStatus() {
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

  function createCard(id, title) {
    if (!cards.hasOwnProperty(id)) {
      const root = document.createElement('div');
      const titleArea = document.createElement('div');
      const valueArea = document.createElement('div');

      titleArea.innerText = title;
      valueArea.innerText = "-";

      root.classList.add("card");
      root.appendChild(titleArea);
      root.appendChild(valueArea);
      cardContainer.appendChild(root);

      cards[id] = {
        root: root,
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
      cards[key].root.classList.remove("activeCard");
    }

    if (id !== null) {
      cards[id].root.classList.add("activeCard");
      cards[id].root.focus();
      cards[id].root.scrollIntoView({
        behavior: 'smooth',
        block: 'end'
      });
    }
  }

  function setCardValue(id, value) {
    cards[id].value.innerText = value;
    cards[id].root.focus();
    cards[id].root.scrollIntoView({
      behavior: 'smooth',
      block: 'end'
    });
  }

  function resetCards(cards) {
    for (let [id, title] of cards) {
      createCard(id, title);
    }

    cardContainer.scrollTo(0, 0);
  }

  function onVolume(volume) {
    const baseHeight = 8;
    bar1.style.height = `${baseHeight + volume * 20}px`;
    bar2.style.height = `${baseHeight + volume * 32}px`;
    bar3.style.height = `${baseHeight + volume * 24}px`;
  }

  btnInit.onclick = async () => {
    clearError();
    const accessKey = accessKeyInput.value.trim();
    if (!accessKey) {
      setErrorText('Please enter an AccessKey');
      return;
    }

    btnInit.disabled = true;

    try {
      await Picovoice.init(accessKey, {
        setStatusText,
        clearStatus,
        setErrorText,
        clearError,
        resetCards,
        setCardValue,
        onVolume,
      });
      accessKeyInput.classList.add('hidden');
      btnInit.classList.add('hidden');
      cardContainer.classList.remove('hidden');
      btnCancel.classList.remove('hidden');
      volumeMeter.classList.remove('hidden');
      await Picovoice.start();
    } catch (e) {
      setErrorText(e.toString());
    }

    btnInit.disabled = false;
  };

  btnCancel.onclick = async () => {
    btnCancel.disabled = true;

    try {
      await Picovoice.stop();
      btnInit.classList.remove('hidden');
      cardContainer.classList.add('hidden');
      btnCancel.classList.add('hidden');
      volumeMeter.classList.add('hidden');
    } catch (e) {
      setErrorText(e.toString());
    }

    btnCancel.disabled = false;
  };
};

window.onbeforeunload = () => {
  Picovoice.release();
};
