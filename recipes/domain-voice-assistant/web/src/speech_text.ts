const LINK_WORDS = 'the link shown on screen';
const LINK_START_PATTERN = /^(?:https?:|www\.)/i;
const LINK_TAIL_PATTERN = /[.,!?:;]*$/;
const SENTENCE_END_PATTERN = /[.!?]\s+/g;

type Sentence = {
  text: string;
  isEnd: boolean;
};

type SpeechText = {
  push: (text: string) => string;
  flush: () => string;
};

const toWords = (text: string): string[] =>
  text
    .toLowerCase()
    .replace(/[^\p{L}\p{N}'\s]/gu, ' ')
    .split(/\s+/)
    .filter(word => word.length > 0);

const isHeld = (word: string): boolean => {
  const lower = word.toLowerCase();
  return /[.!?:]/.test(word) || 'https'.startsWith(lower) || 'www'.startsWith(lower);
};

const createSpeechText = (validCharacters: Set<string>): SpeechText => {
  let pending = '';
  let isInLink = false;

  const clean = (text: string): string =>
    Array.from(text)
      .map(c => (validCharacters.has(c) ? c : ' '))
      .join('')
      .replace(/ {2,}/g, ' ');

  const push = (text: string): string => {
    pending += text;
    if (isInLink) {
      const space = pending.search(/\s/);
      if (space === -1) {
        return '';
      }
      pending = LINK_TAIL_PATTERN.exec(pending.slice(0, space))![0] + pending.slice(space);
      isInLink = false;
    }

    const start = pending.search(/\S*$/);
    const word = pending.slice(start);
    if (LINK_START_PATTERN.test(word)) {
      const before = pending.slice(0, start);
      pending = word;
      isInLink = true;
      return clean(before) + LINK_WORDS;
    }

    const end = isHeld(word) ? start : pending.length;
    const speech = pending.slice(0, end);
    pending = pending.slice(end);
    return clean(speech);
  };

  const flush = (): string => {
    const speech = isInLink ? LINK_TAIL_PATTERN.exec(pending)![0] : pending;
    pending = '';
    isInLink = false;
    return clean(speech);
  };

  return { push, flush };
};

const splitSentences = (text: string): Sentence[] => {
  const sentences: Sentence[] = [];
  let last = 0;
  for (const match of text.matchAll(SENTENCE_END_PATTERN)) {
    const end = (match.index ?? 0) + match[0].length;
    sentences.push({ text: text.slice(last, end), isEnd: true });
    last = end;
  }
  if (last < text.length) {
    sentences.push({ text: text.slice(last), isEnd: false });
  }
  return sentences;
};

export {
  LINK_WORDS,
  Sentence,
  SpeechText,
  toWords,
  createSpeechText,
  splitSentences,
};
