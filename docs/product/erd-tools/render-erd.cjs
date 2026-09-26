/* Render documentation-native ERD assets from ../erd.md. No database or network access.
 *
 * Usage:
 *   npm run render        # 도메인 5개 뷰 + 전체 상세도
 *   npm run render:views  # 도메인 5개 뷰만
 *   npm run render:full   # 전체 상세도만 (--full-only)
 *
 * 출처: 현재 ERD V2 도식을 생성한 기존 렌더러를 저장소 원본으로 편입한 것이다.
 * 입력 경로와 출력 경로를 저장소 구조에 맞추고, 하드코딩된 컬럼 수 검사를 파싱 결과 계산으로 바꿨다.
 *
 * 컬럼 정의는 ../erd.md의 표에서 파싱하고, 관계는 아래 edges 선언을 따른다.
 * 생성 결과는 ../images/erd-{overview,accounts,lectures,content,learning,full}.{svg,png}다.
 */
const fs = require('node:fs');
const path = require('node:path');
const fullOnly = process.argv.slice(2).includes('--full-only');
const vizModule = require('@viz-js/viz');
const sharp = require('sharp');
const md = fs.readFileSync(path.join(__dirname, '../erd.md'), 'utf8');
const imagesDir = path.join(__dirname, '../images');
const tables = new Map();
let current;
for (const line of md.split(/\r?\n/)) {
  const heading = line.match(/^### \d+(?:\.\d+)+ `([a-z_]+)`$/);
  if (heading) { current = heading[1]; tables.set(current, []); continue; }
  if (/^#{2,4} /.test(line)) current = undefined;
  if (!current || !/^\| `/.test(line)) continue;
  const cells = line.split('|').slice(1, -1).map(s => s.trim());
  if (cells.length !== 6) throw new Error(`Unexpected column row: ${line}`);
  tables.get(current).push({
    name: cells[0].replace(/`/g, ''), type: cells[1] === '미정' ? 'TBD' : cells[1],
    nullable: cells[2] === '미정' ? '?' : cells[2],
    keys: cells[3] === '—' ? '' : cells[3],
    status: cells[4] === '확장 예약' ? 'RESERVED' : '',
  });
}
// [child table, child FK, parent table, parent key]
const edges = [
  ['lectures', 'instructor_user_id', 'users', 'id'],
  ['lectures', 'current_lecture_problem_id', 'lecture_problems', 'id'],
  ['participants', 'lecture_id', 'lectures', 'id'],
  ['participants', 'user_id', 'users', 'id'],
  ['lecture_problems', 'lecture_id', 'lectures', 'id'],
  ['lecture_problems', 'problem_id', 'problems', 'id'],
  ['curriculum_problems', 'curriculum_stage_id', 'curriculum_stages', 'id'],
  ['curriculum_problems', 'problem_id', 'problems', 'id'],
  ['problems', 'creator_user_id', 'users', 'id'],
  ['problem_cells', 'problem_id', 'problems', 'id'],
  ['problem_skills', 'problem_id', 'problems', 'id'],
  ['problem_skills', 'skill_id', 'learning_skills', 'id'],
  ['problem_attempts', 'user_id', 'users', 'id'],
  ['problem_attempts', 'problem_id', 'problems', 'id'],
  ['problem_attempts', 'curriculum_stage_id', 'curriculum_stages', 'id'],
  ['problem_attempts', 'learning_session_id', 'learning_sessions', 'id'],
  ['problem_attempts', 'lecture_id', 'lectures', 'id'],
  ['attempt_cells', 'attempt_id', 'problem_attempts', 'id'],
  ['attempt_skill_results', 'attempt_id', 'problem_attempts', 'id'],
  ['attempt_skill_results', 'skill_id', 'learning_skills', 'id'],
  ['user_skill_memory', 'user_id', 'users', 'id'],
  ['user_skill_memory', 'skill_id', 'learning_skills', 'id'],
  ['learning_sessions', 'user_id', 'users', 'id'],
  ['learning_sessions', 'curriculum_stage_id', 'curriculum_stages', 'id'],
];
const colors = {
  users: '#304973', mail_outbox: '#304973', lectures: '#304973', participants: '#304973', lecture_problems: '#304973',
  curriculum_stages: '#176b65', curriculum_problems: '#176b65', problems: '#176b65',
  problem_cells: '#176b65', learning_skills: '#176b65', problem_skills: '#176b65',
  problem_attempts: '#69448f', attempt_cells: '#69448f', attempt_skill_results: '#69448f', user_skill_memory: '#69448f', learning_sessions: '#69448f',
};
const expected = Object.keys(colors);
// 모든 테이블에 색과 역할이 배정되어 있는지 확인한다. 테이블을 추가하면 colors·roles도 함께 고쳐야 한다.
if (tables.size !== expected.length || expected.some(t => !tables.has(t))) {
  throw new Error(`Table set mismatch: erd.md has ${tables.size}, colors declares ${expected.length}`);
}
const allFields = [...tables.values()].flat();
const column = (table, name) => tables.get(table)?.find(f => f.name === name);
for (const [child, fk, parent, pk] of edges) {
  if (!column(child, fk)?.keys.includes('FK') || !column(parent, pk)?.keys.includes('PK')) {
    throw new Error(`Invalid relation ${child}.${fk} -> ${parent}.${pk}`);
  }
}
const fkCount = allFields.filter(f => f.keys.includes('FK')).length;
if (fkCount !== edges.length) throw new Error(`Unmapped foreign key: erd.md has ${fkCount} FK columns, edges declares ${edges.length}`);
const escape = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const q = s => JSON.stringify(s);
const roles = {users:'계정·역할·인증·탈퇴',mail_outbox:'계정 메일 발송 작업·재시도',lectures:'실제 수업·현재 문제',participants:'수업 참여·퇴장',lecture_problems:'수업과 문제 연결',curriculum_stages:'학습 단계 정의',curriculum_problems:'단계 후보·기본 순서',problems:'검증된 문제 원본',problem_cells:'위치별 정답 점형',learning_skills:'학습 요소 사전',problem_skills:'문제와 평가 요소 연결',learning_sessions:'10문항 배정·완료·분석',problem_attempts:'독립 풀이·최종 채점',attempt_cells:'Cell별 정답·입력·차이',attempt_skill_results:'요소별 정오답·오류 수',user_skill_memory:'요소별 현재 BKT 상태'};
// colors와 마찬가지로 roles도 모든 테이블에 있어야 한다. 없으면 도식에 undefined가 그려지므로 먼저 막는다.
const missingRoles = expected.filter(table => !roles[table]);
if (missingRoles.length > 0) {
  throw new Error(`Missing role descriptions: ${missingRoles.join(', ')}`);
}
function node(table, overview, context = false) {
  const fields = tables.get(table);
  const shown = overview ? [] : context ? fields.filter(f=>f.keys.includes('PK')) : fields;
  const widths=[Math.max(95,...shown.map(f=>f.keys.length*9+30)),Math.max(140,...shown.map(f=>f.name.length*9+40)),Math.max(165,...shown.map(f=>f.type.length*9+40)),170];
  const overviewWidth=Math.max(390,table.length*13+45);
  let html = `<TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="10" COLOR="#d8e0ea">`;
  html += `<TR><TD COLSPAN="4" WIDTH="${overview?overviewWidth:widths.reduce((a,b)=>a+b,0)}" BGCOLOR="${colors[table]}" ALIGN="LEFT"><FONT FACE="Arial" COLOR="white" POINT-SIZE="17"><B>${table}</B></FONT></TD></TR>`;
  html += `<TR><TD COLSPAN="4" ALIGN="LEFT">${roles[table]} · ${fields.length} columns${context?' · reference':''}</TD></TR>`;
  if (!overview) html += '<TR>'+['KEY','COLUMN','TYPE','NULL / SCOPE'].map((h,i)=>`<TD WIDTH="${widths[i]}" BGCOLOR="#edf2f7">${h}</TD>`).join('')+'</TR>';
  for (const f of shown) {
    const bg = f.status === 'ADD' ? '#fff1ca' : f.status === 'RESERVED' ? '#ede9f8' : 'white';
    html += `<TR><TD PORT="in_${f.name}" BGCOLOR="${bg}">${escape(f.keys || '-')}</TD>`;
    html += `<TD ALIGN="LEFT" BGCOLOR="${bg}">${f.name}</TD>`;
    html += `<TD ALIGN="LEFT" BGCOLOR="${bg}">${escape(f.type)}</TD>`;
    html += `<TD PORT="out_${f.name}" BGCOLOR="${bg}">${f.nullable}${f.status ? ` / ${f.status}` : ''}</TD></TR>`;
  }
  return `${q(table)} [label=<${html}</TABLE>>];`;
}
const views = [
  ['overview', '전체 관계도', expected, true, []],
  ['accounts', '계정·인증', ['users', 'mail_outbox'], false, []],
  ['lectures', '실시간 수업', ['lectures', 'participants', 'lecture_problems','users','problems'], false, ['users','problems']],
  ['content', '커리큘럼·문제·학습 요소', ['curriculum_stages', 'curriculum_problems', 'problems', 'problem_cells', 'learning_skills', 'problem_skills', 'users'], false, ['users']],
  ['learning', '개인 세션·풀이·평가·숙련도', ['learning_sessions','problem_attempts', 'attempt_cells', 'attempt_skill_results','user_skill_memory','users','lectures','curriculum_stages','problems','learning_skills'], false, ['users','lectures','curriculum_stages','problems','learning_skills']],
];
async function main() {
  const viz = await vizModule.instance();
  const reports = [];
  const detailedCoverage = new Set();
  const selectedViews = fullOnly ? [['full', '전체 상세도 · 모든 테이블과 컬럼', expected, false, []]] : views;
  for (const [suffix, title, included, overview, contexts] of selectedViews) {
    if(!overview) for(const table of included) if(!contexts.includes(table)) detailedCoverage.add(table);
    const set = new Set(included);
    const dot = [
      'digraph ERD {',
      `graph [rankdir=LR, bgcolor="white", pad="0.5", nodesep="0.55", ranksep="1.25", splines=polyline, outputorder=edgesfirst, fontname="Malgun Gothic", fontsize=24, labelloc=t, label=${q('I:EUM ERD V2 | '+title + `\n${tables.size} tables / ${allFields.length} columns / ${edges.length} physical FK relationships\nArrow: child FK -> parent key | dashed: nullable FK | teal: JSON reference\n${fullOnly?'All tables show every column.':'Reference nodes show parent keys only.'} Reserved fields are NULL in MVP.`)}];`,
      'node [shape=plain, fontname="Malgun Gothic", fontsize=13];',
      'edge [fontname="Malgun Gothic", fontsize=10, color="#738398", fontcolor="#52637a", arrowsize=0.8, penwidth=1.3];',
      ...included.map(t => node(t, overview, contexts.includes(t))),
    ];
    for (const [child, fk, parent, pk] of edges) {
      if (!set.has(child) || !set.has(parent) || contexts.includes(child)) continue;
      const nullable = column(child, fk).nullable === 'NULL';
      const childPort=overview?'':`:out_${fk}:e`, parentPort=overview?'':`:in_${pk}:w`;
      dot.push(`${q(child)}${childPort} -> ${q(parent)}${parentPort} [style=${nullable ? 'dashed' : 'solid'}, headlabel=${q(nullable ? '0..1' : '1')}, taillabel="0..N", labeldistance=2.2${fk==='current_lecture_problem_id'?', constraint=false':''}];`);
    }
    if(set.has('learning_sessions')&&set.has('problems')) dot.push(`${q('learning_sessions')}${overview?'':':out_assigned_problems:e'} -> ${q('problems')}${overview?'':':in_id:w'} [color="#009688",fontcolor="#00796b",style=dashed,label="JSON problem_id / BE validation",constraint=false];`);
    dot.push('}');
    const source = dot.join('\n');
    const svg = viz.renderString(source, { format: 'svg', engine: 'dot' });
    const base = path.join(imagesDir, `erd-${suffix}`);
    // .dot는 중간 산출물이므로 파일로 남기지 않는다.
    fs.writeFileSync(base + '.svg', svg, 'utf8');
    await sharp(Buffer.from(svg), { density: 125 }).png().toFile(base + '.png');
    const meta = await sharp(base + '.png').metadata();
    reports.push({ name: suffix, tables: included.length, width: meta.width, height: meta.height });
  }
  if(detailedCoverage.size!==tables.size) throw new Error('Missing full-column detail diagram');
  // validation 결과는 파일로 남기지 않고 stdout으로만 보고한다.
  const report={tables: tables.size, columns: allFields.length, relationships: edges.length, logicalReferences:1, detailedCoverage:[...detailedCoverage],diagrams: reports};
  console.log(JSON.stringify(report, null, 2));
}
main().catch(err => { console.error(err); process.exitCode = 1; });
