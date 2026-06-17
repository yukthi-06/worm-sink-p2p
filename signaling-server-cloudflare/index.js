// Cloudflare Worker - WormSink Signaling Server

const WORDS = [
  "abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract", "absurd", "abuse",
  "access", "accident", "account", "accuse", "achieve", "acid", "acoustic", "acquire", "across", "act",
  "action", "active", "actor", "actress", "actual", "adapt", "add", "addict", "address", "adjust",
  "admit", "adult", "advance", "advice", "advise", "aerobic", "affair", "afford", "afraid", "again",
  "age", "agent", "agree", "ahead", "aim", "air", "airport", "aisle", "alarm", "album",
  "alcohol", "alert", "alien", "all", "alley", "allow", "almost", "alone", "alpha", "already",
  "also", "alter", "always", "amateur", "amazing", "among", "amount", "amused", "analyst", "anchor",
  "ancient", "anger", "angle", "angry", "animal", "ankle", "announce", "annual", "another", "answer",
  "antenna", "antique", "anxiety", "any", "apart", "apology", "appear", "apple", "approve", "april",
  "arch", "arctic", "area", "arena", "argue", "arm", "armed", "armor", "army", "around",
  "arrange", "arrest", "arrive", "arrow", "art", "artefact", "artist", "artwork", "ask", "aspect",
  "assault", "asset", "assist", "assume", "asthma", "athlete", "atom", "attack", "attend", "attitude",
  "attract", "auction", "audit", "august", "aunt", "author", "auto", "autumn", "average", "avocado",
  "avoid", "awake", "aware", "away", "awesome", "awful", "awkward", "axis", "baby", "bachelor",
  "bacon", "badge", "bag", "balance", "balcony", "ball", "bamboo", "banana", "banner", "bar",
  "barely", "bargain", "barrel", "barrier", "base", "basic", "basket", "battle", "beach", "bean",
  "beauty", "because", "become", "beef", "before", "begin", "behave", "behind", "belief", "below",
  "belt", "bench", "benefit", "best", "betray", "better", "between", "beyond", "bicycle", "bid",
  "bike", "bind", "biology", "bird", "birth", "bitter", "black", "blade", "blame", "blanket",
  "blast", "bleak", "bless", "blind", "blood", "blossom", "blouse", "blue", "blur", "blush",
  "board", "boat", "body", "boil", "bomb", "bone", "bonus", "book", "boost", "border",
  "boring", "borrow", "boss", "bottom", "bounce", "box", "boy", "bracket", "brain", "brand",
  "brass", "brave", "bread", "breeze", "brick", "bridge", "brief", "bright", "bring", "brisk",
  "broad", "bronze", "brother", "brown", "brush", "bubble", "buddy", "budget", "buffalo", "build",
  "bulb", "bulk", "bullet", "bundle", "bunker", "burden", "burger", "burst", "bus", "business",
  "busy", "butter", "buyer", "buzz", "cabbage", "cabin", "cable", "cactus", "cage", "cake",
  "call", "calm", "camera", "camp", "can", "canal", "cancel", "candy", "cannon", "canoe",
  "canvas", "canyon", "capable", "capital", "captain", "car", "carbon", "card", "cargo", "carpet",
  "carry", "cart", "case", "cash", "casino", "castle", "casual", "cat", "catalog", "catch",
  "category", "cattle", "caught", "cause", "caution", "cave", "cavity", "cease", "ceiling", "celery",
  "cement", "census", "century", "cereal", "certain", "chair", "chalk", "champion", "change", "chaos",
  "chapter", "charge", "chase", "chat", "cheap", "check", "cheese", "chef", "cherry", "chest",
  "chicken", "chief", "child", "chimney", "choice", "choose", "chronic", "chuckle", "chunk", "churn",
  "cigar", "cinnamon", "circle", "citizen", "city", "civil", "claim", "clap", "clarify", "claw",
  "clay", "clean", "clerk", "clever", "click", "client", "cliff", "climb", "clinic", "clip",
  "clock", "clog", "close", "cloth", "cloud", "clown", "club", "clump", "cluster", "clutch",
  "coach", "coal", "coast", "coconut", "code", "coffee", "coil", "coin", "collect", "colony",
  "color", "column", "combine", "come", "comfort", "comic", "common", "company", "concert", "conduct",
  "confirm", "congress", "connect", "consider", "control", "convince", "cook", "cool", "copper", "copy",
  "coral", "core", "corn", "corner", "corona", "correct", "cost", "cotton", "couch", "country",
  "couple", "course", "cousin", "cover", "coyote", "crack", "cradle", "craft", "cram", "crane",
  "crash", "crater", "crawl", "crazy", "cream", "credit", "creek", "crew", "cricket", "crime",
  "crisp", "critic", "crop", "cross", "croup", "crowd", "crucial", "cruel", "cruise", "crumble",
  "crunch", "crush", "cry", "crystal", "cube", "culture", "cup", "cupboard", "curious", "current",
  "curtain", "curve", "cushion", "custom", "cute", "cycle", "dad", "damage", "damp", "dance",
  "danger", "daring", "dash", "daughter", "dawn", "day", "deal", "debate", "debris", "decade",
  "december", "decide", "decline", "decorate", "decrease", "deer", "defense", "define", "defy", "degree",
  "delay", "deliver", "demand", "demise", "denial", "dentist", "deny", "depart", "depend", "deposit",
  "depth", "deputy", "derive", "desert", "design", "desk", "despair", "destroy", "detail", "detect",
  "device", "devote", "diagram", "dial", "diamond", "diary", "dice", "diesel", "diet", "differ",
  "digital", "dignity", "dilemma", "dinner", "dinosaur", "direct", "dirt", "disagree", "discover", "disease",
  "dish", "dismiss", "disorder", "display", "distance", "divert", "divide", "divorce", "dizzy", "doctor",
  "document", "dog", "doll", "dolphin", "domain", "donate", "donkey", "donor", "door", "dose",
  "double", "dove", "draft", "dragon", "drama", "drastic", "draw", "dream", "dress", "drift",
  "drill", "drink", "drip", "drive", "drop", "drum", "dry", "duck", "dumb", "drio",
  "during", "dust", "dutch", "duty", "dwarf", "dynamic", "eager", "eagle", "early", "earn",
  "earth", "easily", "east", "easy", "echo", "ecology", "economy", "edge", "edit", "educate",
  "effort", "egg", "eight", "either", "elbow", "elder", "electric", "elegant", "element", "elephant",
  "elevator", "elvis", "embark", "embody", "embrace", "emerge", "emotion", "employ", "empower", "empty",
  "enable", "enact", "end", "endless", "endorse", "enemy", "energy", "enforce", "engage", "engine",
  "enjoy", "enlist", "enough", "enrich", "enroll", "ensure", "enter", "entire", "entry", "envelope",
  "episode", "equal", "equip", "era", "erase", "erode", "erosion", "error", "erupt", "escape",
  "essay", "essence", "estate", "eternal", "ethics", "evidence", "evil", "evoke", "evolve", "exact",
  "example", "excess", "exchange", "excite", "exclude", "excuse", "execute", "exercise", "exhaust", "exhibit",
  "exile", "exist", "exit", "exotic", "expand", "expect", "expire", "explain", "expose", "express",
  "extend", "extra", "eye", "eyebrow", "fabric", "face", "facets", "facility", "fact", "fade",
  "faint", "faith", "fall", "false", "fame", "family", "famous", "fan", "fancy", "fantasy",
  "farm", "fashion", "fast", "fatal", "father", "fatigue", "fault", "favorite", "feature", "february",
  "federal", "fee", "feed", "feel", "female", "fence", "festival", "fetch", "fever", "few",
  "fiber", "fiction", "filter", "final", "find", "fine", "finger", "finish", "fire", "firm",
  "first", "fiscal", "fish", "fit", "fitness", "fix", "flag", "flame", "flash", "flat",
  "flavor", "flee", "flight", "flip", "float", "flock", "floor", "flower", "fluid", "flush",
  "fly", "foam", "focus", "fog", "foil", "fold", "follow", "food", "foot", "force",
  "forest", "forget", "fork", "fortune", "forward", "fossil", "foster", "found", "fox", "fragile",
  "frame", "frequent", "fresh", "friend", "fringe", "frog", "front", "frost", "frown", "frozen",
  "fruit", "fuel", "fun", "funny", "furnace", "fury", "future", "gadget", "gain", "galaxy",
  "gale", "gallery", "game", "gap", "garage", "garbage", "garden", "garlic", "garment", "gas",
  "gasp", "gate", "gather", "gauge", "gaze", "general", "genius", "genre", "gentle", "genuine",
  "gesture", "ghost", "giant", "gift", "giggle", "ginger", "giraffe", "girl", "give", "glad",
  "glance", "glare", "glass", "glide", "glimmer", "globe", "gloom", "glory", "glove", "glow",
  "glue", "goat", "goddess", "gold", "good", "goose", "gorilla", "gospel", "gossip", "govern",
  "gown", "grab", "grace", "grain", "grant", "grape", "grass", "gravity", "gravy", "gray",
  "great", "green", "grid", "grief", "grit", "grocery", "groom", "group", "grow", "grunt",
  "guard", "guess", "guide", "guilt", "guitar", "gun", "gym", "habit", "hair", "half",
  "hammer", "hand", "hanger", "hangar", "happy", "hard", "harsh", "harvest", "hat", "have",
  "hawk", "hazard", "head", "health", "heart", "heavy", "hedgehog", "height", "hello", "helmet",
  "help", "hen", "hero", "hidden", "high", "hill", "hint", "hip", "hire", "history",
  "hobby", "hockey", "hold", "hole", "holiday", "hollow", "home", "honey", "hood", "hope",
  "horn", "horse", "hospital", "host", "hotel", "hour", "house", "hover", "how", "huge",
  "human", "humble", "humor", "hundred", "hungry", "hunt", "hurdle", "hurry", "hurt", "husband",
  "hybrid", "ice", "icon", "idea", "identify", "idle", "jacket", "jaguar", "jail", "jam",
  "january", "jar", "jasmine", "jealous", "jeans", "jelly", "jewel", "job", "join", "joke",
  "journal", "journey", "joy", "judge", "juice", "july", "jump", "june", "jungle", "junior",
  "junk", "just", "kangaroo", "keen", "keep", "ketchup", "key", "kick", "kid", "kidney",
  "kind", "kingdom", "kiss", "kit", "kitchen", "kite", "kitten", "kiwi", "knee", "knife",
  "knock", "know", "lab", "label", "labor", "ladder", "lady", "lake", "lamp", "language",
  "laptop", "large", "later", "latin", "laugh", "laundry", "lava", "law", "lawn", "lawyer",
  "lay", "layer", "lazy", "leader", "leaf", "learn", "lease", "leather", "leave", "lecture",
  "left", "leg", "legal", "legend", "leisure", "lemon", "lend", "length", "lens", "leopard",
  "lesson", "letter", "level", "liar", "liberty", "library", "license", "life", "lift", "light",
  "like", "limb", "limit", "link", "lion", "liquid", "list", "listen", "little", "live",
  "lizard", "load", "loan", "lobster", "local", "lock", "locomotive", "log", "logic", "lonely",
  "long", "loop", "lottery", "loud", "lounge", "love", "loyal", "lucky", "luggage", "lumber",
  "lunar", "lunch", "luxury", "lyrics", "machine", "mad", "magic", "magnet", "magnify", "mail",
  "main", "major", "make", "mammal", "man", "manage", "mandarin", "mango", "mansion", "manual",
  "maple", "marble", "march", "margin", "marine", "market", "marriage", "mask", "mass", "master",
  "match", "material", "maths", "matrix", "matter", "maximum", "may", "maybe", "mayor", "maze",
  "meadow", "mean", "measure", "meat", "mechanic", "medal", "media", "melody", "melt", "member",
  "memory", "mention", "menu", "mercy", "merge", "merit", "merry", "mesh", "message", "metal",
  "method", "middle", "midnight", "midst", "mild", "mile", "milk", "mill", "mind", "mine",
  "mineral", "miniature", "minimum", "minor", "mint", "minute", "miracle", "mirror", "mischief", "mix",
  "mixed", "mixture", "mobile", "model", "modify", "module", "moist", "moment", "monastery", "monday",
  "money", "monkey", "monstrous", "month", "monument", "mood", "moon", "moral", "more", "morning",
  "mosquito", "mother", "motion", "motor", "mountain", "mouse", "mouth", "move", "movie", "much",
  "mud", "muffin", "mule", "multiply", "muscle", "museum", "mushroom", "music", "must", "mutual",
  "myself", "mystery", "myth", "naive", "name", "napkin", "narrow", "nasty", "nation", "nature",
  "near", "nearby", "nearly", "neat", "nebula", "necessary", "neck", "need", "negative", "neglect",
  "neighbor", "neither", "nephew", "nerve", "nest", "net", "network", "neutral", "never", "news",
  "next", "nice", "niche", "nickel", "niece", "night", "nimble", "nine", "nobility", "noble",
  "noise", "nominee", "noodle", "noon", "nor", "north", "nose", "notable", "note", "nothing",
  "notice", "novel", "november", "now", "nuclear", "number", "nurse", "nut", "oak", "oasis",
  "oath", "obey", "object", "oblige", "obscure", "observe", "obtain", "obvious", "occur", "ocean",
  "october", "odor", "off", "offer", "office", "often", "oil", "okay", "old", "olive",
  "olympic", "omit", "once", "one", "onion", "online", "only", "open", "opera", "opinion",
  "oppose", "option", "orange", "orbit", "orchard", "order", "ordinary", "organ", "orient", "original",
  "orphan", "ostrich", "other", "outdoor", "outer", "outline", "output", "outside", "oval", "oven",
  "over", "own", "owner", "oxygen", "oyster", "ozone", "pact", "paddle", "page", "pair",
  "palace", "palm", "panda", "panel", "panic", "panther", "paper", "parade", "parent", "park",
  "parrot", "part", "partner", "party", "pass", "patch", "path", "patient", "patriot", "patrol",
  "pattern", "pause", "pave", "payment", "peace", "peach", "peak", "pear", "pebble", "pecan",
  "pedal", "peer", "pen", "penalty", "pencil", "people", "pepper", "perfect", "permit", "person",
  "pet", "phone", "photo", "phrase", "physical", "piano", "picnic", "picture", "piece", "pig",
  "pigeon", "pill", "pilot", "pink", "pioneer", "pipe", "pistol", "pitch", "pizza", "place",
  "plain", "plan", "plane", "planet", "plastic", "plate", "play", "please", "pledge", "pluck",
  "plug", "plunge", "poem", "poet", "point", "poison", "pole", "police", "policy", "polish",
  "pond", "pony", "pool", "popular", "portion", "position", "positive", "post", "potato", "pottery",
  "poverty", "powder", "power", "practice", "praise", "predict", "prefer", "prepare", "present", "pretty",
  "prevent", "price", "pride", "primary", "prince", "princess", "print", "prior", "prison", "private",
  "prize", "problem", "process", "produce", "profit", "program", "project", "promote", "prompt", "proof",
  "property", "prophet", "protect", "proud", "prove", "provide", "public", "pudding", "pull", "pulp",
  "pulse", "pumpkin", "punch", "pupil", "puppy", "purchase", "purity", "purpose", "purse", "push",
  "put", "puzzle", "pyramid", "quality", "quantum", "quarter", "queen", "query", "quest", "queue",
  "quick", "quiet", "quilt", "quite", "quiz", "quote", "rabbit", "raccoon", "race", "radar",
  "radio", "rail", "rain", "raise", "rally", "ramp", "ranch", "random", "range", "rapid",
  "rare", "rate", "rather", "raven", "raw", "razor", "ready", "real", "reason", "rebel",
  "rebuild", "recall", "receive", "recipe", "record", "recycle", "red", "reduce", "reflect", "reform",
  "refuse", "region", "regret", "regular", "reject", "relax", "release", "relief", "rely", "remain",
  "remember", "remind", "remove", "render", "renew", "rent", "reopen", "repair", "repeat", "replace",
  "report", "require", "rescue", "resemble", "resist", "resource", "respond", "results", "retire", "retreat",
  "return", "reunion", "reveal", "review", "reward", "rhythm", "rib", "ribbon", "rice", "rich",
  "ride", "ridge", "rifle", "right", "rigid", "ring", "riot", "ripple", "rose", "rotate",
  "rough", "round", "route", "royal", "rubber", "rude", "rug", "rule", "run", "runway", "rural",
  "sad", "saddle", "sadness", "safe", "safety", "sage", "sail", "salad", "salmon", "salon", "salt",
  "salute", "same", "sample", "sand", "satisfy", "saturday", "sauce", "sausage", "save", "say",
  "scale", "scan", "scare", "scatter", "scenario", "scene", "scent", "scheme", "school", "science",
  "scissors", "scooter", "scope", "score", "scout", "scrap", "screen", "screw", "script", "scrub",
  "sea", "search", "season", "seat", "second", "secret", "section", "sector", "secure", "security",
  "seed", "seek", "segment", "select", "sell", "seminar", "senior", "sense", "sentence", "september",
  "serenity", "series", "serious", "servant", "serve", "service", "session", "settle", "setup",
  "seven", "shadow", "shaft", "shallow", "shame", "shape", "share", "shark", "sharp", "shawl",
  "she", "shed", "shell", "sheriff", "shield", "shift", "shift", "shine", "ship", "shiver",
  "shock", "shoe", "shoot", "shop", "short", "shoulder", "shove", "show", "shower", "shrub",
  "shrug", "shuffle", "shun", "shutter", "shy", "sibling", "sick", "side", "siege", "sight",
  "sign", "silent", "silk", "silly", "silver", "similar", "simple", "since", "sing", "siren",
  "sister", "situate", "six", "size", "skate", "sketch", "ski", "skill", "skin", "skirt",
  "skull", "sky", "slate", "sleep", "slice", "slide", "slight", "slim", "slogan", "slot",
  "slow", "slum", "slump", "small", "smart", "smash", "smell", "smile", "smoke", "smooth",
  "snack", "snake", "snare", "sneeze", "sniff", "snow", "soap", "soccer", "social", "sock",
  "soda", "soft", "solar", "soldier", "solid", "solve", "some", "song", "soon", "sorry",
  "sort", "soul", "sound", "soup", "source", "south", "space", "spare", "spatial", "speak",
  "special", "speed", "spell", "spend", "sphere", "spice", "spider", "spike", "spin",
  "spirit", "spit", "spoil", "sponsor", "spoon", "sport", "spot", "spray", "spread",
  "spring", "spy", "square", "squeeze", "squirrel", "stable", "stadium", "staff", "stage",
  "stairs", "stamp", "stand", "start", "state", "stay", "steak", "steel", "stem",
  "step", "stereo", "steward", "stick", "still", "sting", "stock", "stomach", "stone",
  "stool", "store", "storm", "story", "strap", "straw", "stray", "street", "strength",
  "stretch", "strike", "string", "strip", "strive", "strong", "struggle", "student", "studio",
  "study", "stuff", "stumble", "style", "subject", "submit", "subway", "success", "such",
  "sudden", "suffer", "sugar", "suggest", "suit", "summer", "sun", "sunday", "sunny",
  "sunset", "super", "supply", "support", "supreme", "sure", "surface", "surge", "surprise",
  "surround", "survey", "suspect", "sustain", "swallow", "swamp", "swap", "swarm", "swear",
  "sweet", "swift", "swim", "swing", "switch", "sword", "symbol", "symptom", "syrup",
  "system", "table", "tackle", "tag", "tail", "talent", "talk", "tank", "tape",
  "target", "task", "taste", "tattoo", "taxi", "teach", "team", "tell", "temple",
  "tenant", "tennis", "tent", "term", "test", "text", "thank", "that", "theme",
  "then", "theory", "there", "they", "thing", "think", "third", "this", "thought",
  "three", "thrive", "throw", "thumb", "thunder", "ticket", "tide", "tiger", "tilt",
  "timber", "time", "tiny", "tip", "tired", "tissue", "title", "toast", "tobacco",
  "today", "toddler", "toe", "together", "toilet", "token", "tomato", "tomorrow", "tone",
  "tongue", "tonight", "tool", "tooth", "top", "topic", "topple", "torch", "tornado",
  "tortoise", "toss", "total", "tourist", "toward", "tower", "town", "toy", "track",
  "trade", "traffic", "tragedy", "train", "transfer", "trap", "trash", "travel", "tray",
  "treat", "tree", "trend", "trial", "tribe", "trick", "trigger", "trim", "trio",
  "trip", "trophy", "trouble", "truck", "true", "truly", "trumpet", "trust", "truth",
  "try", "tube", "tuesday", "tuition", "tumble", "tuna", "tundra", "tunnel", "turkey",
  "turn", "turtle", "twelve", "twenty", "twice", "twin", "twist", "two", "type",
  "typical", "ugly", "umbrella", "unable", "unaware", "uncle", "uncover", "under", "undo",
  "unfair", "unfold", "unhappy", "uniform", "unique", "unit", "universe", "unknown", "unlock",
  "until", "unusual", "unveil", "update", "upgrade", "uphold", "upon", "upper", "upset",
  "urban", "urge", "usage", "use", "used", "useful", "useless", "usual", "utility",
  "vacant", "vacuum", "vague", "valid", "valley", "valve", "van", "vanish", "vapor",
  "various", "vast", "vault", "vector", "vegetable", "vehicle", "velvet", "vendor", "venture",
  "venue", "verb", "verdict", "verify", "version", "very", "vessel", "veteran", "viable",
  "vibrant", "vicious", "victory", "video", "view", "village", "vintage", "violin", "virtual",
  "virus", "visa", "visit", "visual", "vital", "vivid", "vocal", "voice", "void",
  "volcano", "volume", "vote", "voyage", "wage", "wagon", "wait", "wake", "walk",
  "wall", "walnut", "want", "warfare", "warm", "warrior", "wash", "wasp", "waste",
  "water", "wave", "way", "wealth", "weapon", "wear", "weasel", "weather", "web",
  "wedding", "wednesday", "weekend", "weekly", "weigh", "weird", "welcome", "west", "wet",
  "whale", "what", "wheat", "wheel", "when", "where", "whether", "which", "while",
  "whisper", "whistle", "who", "whole", "why", "wide", "width", "wife", "wild",
  "will", "win", "wind", "window", "windy", "wine", "wing", "wink", "winner",
  "winter", "wire", "wisdom", "wise", "wish", "witness", "wolf", "woman", "wonder",
  "wood", "wool", "word", "work", "world", "worry", "worth", "wrap", "wreck",
  "wrestle", "wrist", "write", "wrong", "yard", "year", "yellow", "yes", "yesterday",
  "yet", "yield", "you", "young", "your", "yourself", "youth", "zebra", "zero",
  "zone", "zoo"
];

const sessions = new Map();

function generateCode() {
  const selected = [];
  const bytes = new Uint32Array(4);
  crypto.getRandomValues(bytes);
  for (let i = 0; i < 4; i++) {
    const idx = bytes[i] % WORDS.length;
    selected.push(WORDS[idx]);
  }
  return selected.join("-");
}

async function getSessionData(env, code) {
  if (env.SESSIONS) {
    const data = await env.SESSIONS.get(code);
    return data ? JSON.parse(data) : null;
  }
  return sessions.get(code) || null;
}

async function saveSessionData(env, code, data) {
  const serialized = JSON.stringify(data);
  if (env.SESSIONS) {
    await env.SESSIONS.put(code, serialized, { expirationTtl: 3600 }); // Expire after 1 hour
  } else {
    sessions.set(code, data);
    setTimeout(() => {
      sessions.delete(code);
    }, 3600 * 1000);
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;

    // CORS Headers
    const corsHeaders = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
    };

    if (request.method === "OPTIONS") {
      return new Response(null, { headers: corsHeaders });
    }

    try {
      if (path === "/session/create" && request.method === "POST") {
        const code = generateCode();
        const sessionState = {
          code,
          offer: null,
          answer: null,
          candidates: []
        };
        await saveSessionData(env, code, sessionState);
        return new Response(JSON.stringify({ code }), {
          headers: { ...corsHeaders, "Content-Type": "application/json" }
        });
      }

      if (path === "/session/offer" && request.method === "POST") {
        const { code, sdp } = await request.json();
        const session = await getSessionData(env, code);
        if (!session) {
          return new Response("Session not found", { status: 404, headers: corsHeaders });
        }
        session.offer = sdp;
        await saveSessionData(env, code, session);
        return new Response("OK", { headers: corsHeaders });
      }

      if (path === "/session/answer" && request.method === "POST") {
        const { code, sdp } = await request.json();
        const session = await getSessionData(env, code);
        if (!session) {
          return new Response("Session not found", { status: 404, headers: corsHeaders });
        }
        session.answer = sdp;
        await saveSessionData(env, code, session);
        return new Response("OK", { headers: corsHeaders });
      }

      if (path === "/session/candidate" && request.method === "POST") {
        const { code, candidate, sdpMid, sdpMLineIndex } = await request.json();
        const session = await getSessionData(env, code);
        if (!session) {
          return new Response("Session not found", { status: 404, headers: corsHeaders });
        }
        session.candidates.push({ candidate, sdpMid, sdpMLineIndex });
        await saveSessionData(env, code, session);
        return new Response("OK", { headers: corsHeaders });
      }

      const match = path.match(/^\/session\/([a-z-]+)$/);
      if (match && request.method === "GET") {
        const code = match[1];
        const session = await getSessionData(env, code);
        if (!session) {
          return new Response("Session not found", { status: 404, headers: corsHeaders });
        }
        return new Response(JSON.stringify(session), {
          headers: { ...corsHeaders, "Content-Type": "application/json" }
        });
      }

      return new Response("Not Found", { status: 404, headers: corsHeaders });
    } catch (e) {
      return new Response(e.message, { status: 500, headers: corsHeaders });
    }
  }
};
