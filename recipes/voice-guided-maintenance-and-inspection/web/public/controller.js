window.onload = () => {
  const titleText = document.getElementById('titleText');
  const errorText = document.getElementById('errorText');
  const statusText = document.getElementById('statusText');
  const accessKeyInput = document.getElementById('accessKey');
  const btnInit = document.getElementById('btnInit');

  const appContainer = document.getElementById('appContainer');
  const cardContainer = document.getElementById('cardContainer');
  const btnCancel = document.getElementById('btnCancel');

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
        createCard,
        setCardValue,
      });
      accessKeyInput.classList.add('hidden');
      btnInit.classList.add('hidden');
      appContainer.classList.remove('hidden');
      await Picovoice.start();
    } catch (e) {
      btnInit.disabled = false;
    }
  };
};

window.onbeforeunload = () => {
  Picovoice.release();
};
