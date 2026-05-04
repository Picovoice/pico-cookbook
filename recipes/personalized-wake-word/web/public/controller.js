window.onload = () => {
  const accessKeyInput = document.getElementById('accessKey');
  const btnInit = document.getElementById('btnInit');
  const initContainer = document.getElementById('initContainer');
  const appContainer = document.getElementById('appContainer');

  const statusText = document.getElementById('statusText');
  const resultIcon = document.getElementById('resultIcon');
  const resultText = document.getElementById('resultText');

  const enrollProgress = document.getElementById('enrollProgress');
  const volumeMeter = document.getElementById('volumeMeter');
  const bar1 = document.getElementById('bar1');
  const bar2 = document.getElementById('bar2');
  const bar3 = document.getElementById('bar3');

  const buttonContainer = document.getElementById('buttonContainer');
  const btnStartEnroll = document.getElementById('btnStartEnroll');
  const btnStartTest = document.getElementById('btnStartTest');
  const btnCancel = document.getElementById('btnCancel');

  let hasEnrolled = false;
  let currentState = 'IDLE';

  const callbacks = {
    onVolume: volume => {
      const baseHeight = 8;
      bar1.style.height = `${baseHeight + volume * 20}px`;
      bar2.style.height = `${baseHeight + volume * 32}px`;
      bar3.style.height = `${baseHeight + volume * 24}px`;
    },
    onEnrollProgress: progress => {
      enrollProgress.value = progress;
    },
    onEnrollComplete: () => {
      hasEnrolled = true;
      updateUIForState('IDLE');
    },
    onWakeWordRecognized: (isVerified, score) => {
      showTestResult(isVerified, score);
    },
    onError: err => {
      alert('Error: ' + err);
    },
  };

  btnInit.onclick = async () => {
    const accessKey = accessKeyInput.value.trim();
    if (!accessKey) {
      alert('Please enter an AccessKey');
      return;
    }

    btnInit.disabled = true;
    btnInit.innerText = 'Initializing...';

    try {
      await Picovoice.init(accessKey, callbacks);
      await Picovoice.startEnrollment();
      updateUIForState('ENROLLING');
      initContainer.classList.add('hidden');
      appContainer.classList.remove('hidden');
    } catch (e) {
      btnInit.disabled = false;
      btnInit.innerText = 'Start Enrollment';
    }
  };

  btnStartEnroll.onclick = async () => {
    await Picovoice.startEnrollment();
    updateUIForState('ENROLLING');
  };

  btnStartTest.onclick = async () => {
    await Picovoice.startTesting();
    updateUIForState('TESTING');
  };

  btnCancel.onclick = async () => {
    await Picovoice.stop();
    updateUIForState('IDLE');
  };

  function updateUIForState(newState) {
    currentState = newState;

    if (currentState === 'ENROLLING') {
      statusText.innerText = `Say the wake word\nuntil the bar is full`;
      statusText.classList.remove('hidden');
      enrollProgress.classList.remove('hidden');
      enrollProgress.value = 0;

      titleText.classList.add('hidden');
      buttonContainer.classList.add('hidden');
      resultIcon.classList.add('hidden');
      resultText.classList.add('hidden');
      volumeMeter.classList.remove('hidden');

      btnCancel.innerText = 'Cancel';
      btnCancel.classList.remove('hidden');
    } else if (currentState === 'TESTING') {
      statusText.innerText = 'Listening for wake word...';
      statusText.classList.remove('hidden');
      enrollProgress.classList.add('hidden');

      titleText.classList.add('hidden');
      buttonContainer.classList.add('hidden');
      resultIcon.classList.add('hidden');
      resultText.classList.add('hidden');
      volumeMeter.classList.remove('hidden');

      btnCancel.innerText = 'Stop Testing';
      btnCancel.classList.remove('hidden');
    } else {
      statusText.innerText = hasEnrolled
        ? 'Ready to Test or Re-Enroll'
        : 'Ready to Enroll';
      statusText.classList.remove('hidden');
      titleText.classList.remove('hidden');

      enrollProgress.classList.add('hidden');
      volumeMeter.classList.add('hidden');
      btnCancel.classList.add('hidden');
      resultIcon.classList.add('hidden');
      resultText.classList.add('hidden');

      buttonContainer.classList.remove('hidden');

      btnStartEnroll.innerText = hasEnrolled ? 'Re-Enroll' : 'Start Enrollment';
      btnStartEnroll.className = hasEnrolled ? 'secondary' : '';

      if (hasEnrolled) {
        btnStartTest.classList.remove('hidden');
      } else {
        btnStartTest.classList.add('hidden');
      }
    }
  }

  function showTestResult(isVerified, score) {
    volumeMeter.classList.add('hidden');
    statusText.classList.add('hidden');
    btnCancel.classList.add('hidden');

    resultIcon.classList.remove('hidden');
    resultText.classList.remove('hidden');

    if (isVerified) {
      resultIcon.innerText = '✅';
      resultText.innerText = `User Verified\nConfidence: ${score.toFixed(2)}`;
      resultText.style.color = 'var(--success-green)';
    } else {
      resultIcon.innerText = '❌';
      resultText.innerText = `User Rejected\nConfidence: ${score.toFixed(2)}`;
      resultText.style.color = 'var(--error-red)';
    }

    setTimeout(() => resultIcon.classList.add('show'), 10);

    setTimeout(() => {
      resultIcon.classList.remove('show');
      setTimeout(() => {
        resultIcon.classList.add('hidden');
        resultText.classList.add('hidden');
        statusText.classList.remove('hidden');
        volumeMeter.classList.remove('hidden');
        btnCancel.classList.remove('hidden');
      }, 300);
    }, 2000);
  }
};

window.onbeforeunload = () => {
  Picovoice.release();
};
