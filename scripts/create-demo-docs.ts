import PDFDocument from "pdfkit";
import { createWriteStream } from "node:fs";
import { mkdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { finished } from "node:stream/promises";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const docsDirectory = path.join(projectRoot, "docs");

const documents = [
  {
    file: "01-algebra-foundations.pdf",
    title: "Algebra Foundations",
    paragraphs: [
      "A variable is a symbol that represents an unknown or changing number. An expression combines numbers, variables, and operations, while an equation states that two expressions are equal.",
      "To solve a linear equation, keep both sides balanced. Apply the same operation to each side. For example, 3x + 5 = 20 becomes 3x = 15 after subtracting 5, then x = 5 after dividing by 3.",
      "The distributive property states a(b + c) = ab + ac. Like terms have the same variable parts and can be combined: 4x + 2x = 6x. Unlike terms such as 4x and 2y cannot be combined.",
      "A ratio compares quantities by division. A proportion states that two ratios are equal. Cross multiplication can solve a proportion when the denominators are nonzero.",
    ],
  },
  {
    file: "02-physics-motion-and-forces.pdf",
    title: "Physics: Motion and Forces",
    paragraphs: [
      "Distance is the total length traveled, while displacement is the change in position in a particular direction. Average speed equals distance divided by elapsed time. Average velocity equals displacement divided by elapsed time.",
      "Acceleration describes how quickly velocity changes. For constant acceleration, final velocity equals initial velocity plus acceleration multiplied by time: v = u + at.",
      "Newton's first law says an object keeps its state of rest or uniform straight-line motion unless a net external force acts on it. Newton's second law is F = ma, where force is measured in newtons, mass in kilograms, and acceleration in metres per second squared.",
      "Weight is the gravitational force on an object and can be calculated as W = mg near Earth's surface. Mass measures inertia and does not change with location; weight can change when gravitational field strength changes.",
    ],
  },
  {
    file: "03-biology-cells-and-energy.pdf",
    title: "Biology: Cells and Energy",
    paragraphs: [
      "The cell is the basic structural and functional unit of life. The cell membrane controls movement of substances into and out of the cell. The cytoplasm is where many chemical reactions take place.",
      "The nucleus stores most of a eukaryotic cell's genetic material and helps regulate cell activities. Mitochondria release usable energy from food molecules during cellular respiration.",
      "Photosynthesis occurs mainly in chloroplasts. Plants use light energy to convert carbon dioxide and water into glucose and oxygen. The balanced summary equation is carbon dioxide plus water, in the presence of light, produces glucose and oxygen.",
      "Cellular respiration transfers energy from glucose into ATP, which cells use for work. Respiration occurs in plants and animals; photosynthesis is limited to organisms that have photosynthetic pigments.",
    ],
  },
  {
    file: "04-chemistry-matter-and-reactions.pdf",
    title: "Chemistry: Matter and Reactions",
    paragraphs: [
      "Matter has mass and occupies space. A pure substance has a fixed composition. Elements contain one type of atom, while compounds contain two or more elements chemically bonded in fixed proportions.",
      "A physical change alters form or state without making a new substance. A chemical change forms new substances. Evidence can include gas production, a lasting color change, temperature change, or formation of a precipitate.",
      "Atoms contain protons and neutrons in a nucleus, with electrons around it. The atomic number equals the number of protons. In a neutral atom, the number of electrons equals the number of protons.",
      "Chemical equations must be balanced because atoms are conserved in a reaction. Changing coefficients balances atom counts; changing subscripts would change the identity of a substance.",
    ],
  },
  {
    file: "05-english-grammar-basics.pdf",
    title: "English Grammar: Sentence Basics",
    paragraphs: [
      "A complete English sentence normally has a subject and a predicate. The subject identifies who or what the sentence is about. The predicate contains a verb and says something about the subject.",
      "A noun names a person, place, thing, or idea. A pronoun can replace a noun. A verb expresses an action or state. An adjective describes a noun, while an adverb often modifies a verb, adjective, or another adverb.",
      "In the simple present, add s or es to most verbs after he, she, or it: 'She studies.' Use the base form with I, you, we, and they: 'They study.'",
      "A paragraph develops one main idea. A clear topic sentence introduces that idea, supporting sentences explain it with reasons or examples, and a concluding sentence closes the thought.",
    ],
  },
];

async function writePdf(fileName: string, title: string, paragraphs: string[]): Promise<void> {
  const outputPath = path.join(docsDirectory, fileName);
  const document = new PDFDocument({ size: "A4", margin: 56, info: { Title: title } });
  const output = document.pipe(createWriteStream(outputPath));
  document.font("Helvetica-Bold").fontSize(22).text(title, { align: "center" });
  document.moveDown(1.5).font("Helvetica").fontSize(12);
  for (const paragraph of paragraphs) {
    document.text(paragraph, { align: "left", lineGap: 4 });
    document.moveDown(0.8);
  }
  document.end();
  await finished(output);
}

await mkdir(docsDirectory, { recursive: true });
for (const document of documents) {
  await writePdf(document.file, document.title, document.paragraphs);
}
console.log(`Generated ${documents.length} original educational sample PDFs in docs/.`);
