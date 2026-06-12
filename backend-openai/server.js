import 'dotenv/config';
import express from 'express';
import cors from 'cors';
import multer from 'multer';
import OpenAI, { toFile } from 'openai';

const app = express();
const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 12 * 1024 * 1024 } });
const openai = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });
const port = Number(process.env.PORT || 8787);
const transcribeModel = process.env.OPENAI_TRANSCRIBE_MODEL || 'whisper-1';
const textModel = process.env.OPENAI_TEXT_MODEL || 'gpt-4o-mini';

app.use(cors());
app.use(express.json({ limit: '2mb' }));

app.get('/health', (req, res) => {
  res.json({ ok: true, service: 'LTM UniversalVoice backend' });
});

app.post('/translate-audio', upload.single('audio'), async (req, res) => {
  try {
    if (!process.env.OPENAI_API_KEY) {
      return res.status(500).json({ error: 'OPENAI_API_KEY is missing' });
    }
    if (!req.file?.buffer?.length) {
      return res.status(400).json({ error: 'audio file is missing' });
    }

    const source = String(req.body.source || 'auto').trim().toLowerCase();
    const target = String(req.body.target || 'ru').trim().toLowerCase();
    const audioFile = await toFile(req.file.buffer, req.file.originalname || 'audio.wav', { type: req.file.mimetype || 'audio/wav' });

    const transcriptionPayload = {
      file: audioFile,
      model: transcribeModel,
      response_format: 'json'
    };
    if (source && source !== 'auto') transcriptionPayload.language = normalizeLanguageForWhisper(source);

    const transcript = await openai.audio.transcriptions.create(transcriptionPayload);
    const text = String(transcript.text || '').trim();

    if (!text) {
      return res.json({ text: '', translatedText: '' });
    }

    const translated = await translateText(text, target);
    res.json({ text, translatedText: translated, source, target });
  } catch (error) {
    const message = error?.response?.data || error?.message || String(error);
    res.status(500).json({ error: message });
  }
});

async function translateText(text, target) {
  const targetName = readableLanguage(target);
  const completion = await openai.chat.completions.create({
    model: textModel,
    temperature: 0.1,
    messages: [
      {
        role: 'system',
        content: `Translate the user's speech into ${targetName}. Return only the translated speech. Keep names and numbers accurate. Do not add comments.`
      },
      { role: 'user', content: text }
    ]
  });
  return String(completion.choices?.[0]?.message?.content || '').trim();
}

function normalizeLanguageForWhisper(value) {
  const v = value.toLowerCase();
  if (v.startsWith('en')) return 'en';
  if (v.startsWith('ru')) return 'ru';
  if (v.startsWith('de')) return 'de';
  if (v.startsWith('fr')) return 'fr';
  if (v.startsWith('es')) return 'es';
  if (v.startsWith('it')) return 'it';
  if (v.startsWith('pt')) return 'pt';
  if (v.startsWith('pl')) return 'pl';
  if (v.startsWith('uk')) return 'uk';
  if (v.startsWith('tr')) return 'tr';
  return v.slice(0, 2);
}

function readableLanguage(value) {
  const v = value.toLowerCase();
  if (v.startsWith('ru')) return 'Russian';
  if (v.startsWith('en')) return 'English';
  if (v.startsWith('de')) return 'German';
  if (v.startsWith('fr')) return 'French';
  if (v.startsWith('es')) return 'Spanish';
  if (v.startsWith('it')) return 'Italian';
  if (v.startsWith('pt')) return 'Portuguese';
  if (v.startsWith('pl')) return 'Polish';
  if (v.startsWith('uk')) return 'Ukrainian';
  if (v.startsWith('tr')) return 'Turkish';
  return value || 'Russian';
}

app.listen(port, '0.0.0.0', () => {
  console.log(`LTM UniversalVoice backend listening on http://0.0.0.0:${port}`);
});
