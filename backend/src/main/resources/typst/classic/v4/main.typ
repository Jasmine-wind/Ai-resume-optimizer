// Classic v4 — built-in read-only template. Presentation only; all content comes from the
// generated data.typ where every user value is an escaped string literal.
#import "data.typ": resume

#let accent = rgb("#243b5a")
#let muted = rgb("#5f6875")
#let rule = rgb("#cbd2da")

#set page(paper: "a4", margin: (x: 1.75cm, y: 1.25cm))
#set text(
  font: "Noto Sans CJK SC",
  size: 10pt,
  weight: "regular",
  lang: "zh",
)
#set par(leading: 0.36em, justify: false)

#let basics = resume.basics
#let sections = resume.sections

#let education-contains(value) = {
  if value == "" { return false }
  for section in sections {
    if section.kind == "EDUCATION" {
      for entry in section.entries {
        if entry.degree == value { return true }
      }
    }
  }
  false
}

#let contact-line(contact) = contact.value

#let date-range(entry) = {
  let values = ()
  if entry.start-date != "" {
    if entry.end-date != "" {
      values.push(entry.start-date + " – " + entry.end-date)
    } else {
      values.push(entry.start-date)
    }
  } else if entry.end-date != "" {
    values.push(entry.end-date)
  }
  values.join("")
}

#let entry-heading(entry) = {
  if entry.organization != "" { entry.organization }
  else if entry.school != "" { entry.school }
  else { "" }
}

#let entry-subline(entry) = {
  let values = ()
  if entry.degree != "" { values.push(entry.degree) }
  if entry.major != "" { values.push(entry.major) }
  if entry.location != "" { values.push(entry.location) }
  values.join(" · ")
}

#let render-header() = {
  if basics.name != "" {
    align(left, text(size: 19pt, weight: "bold", fill: accent, basics.name))
    v(0.10em)
  }
  if basics.contacts.len() > 0 {
    align(left, text(size: 9.3pt, fill: muted,
      basics.contacts.map(contact-line).join("  ·  ")))
    v(0.08em)
  }
  if basics.job-intention != "" or (basics.highest-education != "" and education-contains(basics.highest-education) == false) {
    let values = ()
    if basics.job-intention != "" { values.push("求职意向：" + basics.job-intention) }
    if basics.highest-education != "" { values.push("最高学历：" + basics.highest-education) }
    align(left, text(size: 9.3pt, fill: muted, values.join("  ·  ")))
    v(0.08em)
  }
  line(length: 100%, stroke: 0.8pt + accent)
}

#let section-title(title) = {
  v(0.34em)
  text(size: 11.8pt, weight: "bold", fill: accent, title)
  v(0.10em)
  line(length: 100%, stroke: 0.45pt + rule)
  v(0.13em)
}

#let render-title-role(entry) = {
  let heading = entry-heading(entry)
  if heading != "" { text(weight: "bold", heading) }
  if heading != "" and entry.role != "" { text(fill: muted, " · ") }
  if entry.role != "" { text(size: 9.5pt, fill: muted, entry.role) }
}

#let render-experience-header(entry) = {
  let heading = entry-heading(entry)
  let dates = date-range(entry)
  if heading != "" or entry.role != "" or dates != "" {
    grid(
      columns: (1fr, auto),
      gutter: 0.8em,
      align: (left, right),
      render-title-role(entry),
      text(size: 9pt, fill: muted, dates),
    )
  }
  if entry.location != "" {
    v(0.02em)
    text(size: 9pt, fill: muted, entry.location)
  }
}

#let render-education-header(entry) = {
  let heading = entry-heading(entry)
  let details = ()
  if entry.degree != "" { details.push(entry.degree) }
  if entry.major != "" { details.push(entry.major) }
  let dates = date-range(entry)
  if heading != "" or details.len() > 0 or dates != "" {
    grid(
      columns: (1fr, auto),
      gutter: 0.8em,
      align: (left, right),
      {
        if heading != "" { text(weight: "bold", heading) }
        if details.len() > 0 {
          if heading != "" { text(fill: muted, "  ") }
          text(size: 9.5pt, fill: muted, details.join(" · "))
        }
      },
      text(size: 9pt, fill: muted, dates),
    )
  }
  if entry.location != "" {
    v(0.02em)
    text(size: 9pt, fill: muted, entry.location)
  }
}

#let render-bullets(entry, start: 0, end: none) = {
  let end-index = if end == none { entry.bullets.len() } else { end }
  if end-index > start {
    v(0.04em)
    list(
      marker: text(fill: accent)[•],
      indent: 1.1em,
      body-indent: 0.34em,
      spacing: 0.36em,
      ..entry.bullets.slice(start, end-index),
    )
  }
}

#let render-plain-lines(entry, start: 0, end: none) = {
  let end-index = if end == none { entry.bullets.len() } else { end }
  if end-index > start {
    for index in range(start, end-index) {
      let bullet = entry.bullets.at(index)
      if bullet != "" {
        text(bullet)
        linebreak()
        if index < end-index - 1 { v(0.10em) }
      }
    }
  }
}

#let render-structured-entry(entry, kind) = {
  let with-bullets = kind == "EXPERIENCE" or kind == "PROJECT"
  let has-short-first = entry.bullets.len() > 0 and entry.bullets.at(0).len() <= 600
  if has-short-first {
    block(breakable: false, {
      if kind == "EDUCATION" { render-education-header(entry) }
      else { render-experience-header(entry) }
      if with-bullets { render-bullets(entry, end: 1) }
      else { render-plain-lines(entry, end: 1) }
    })
    if entry.bullets.len() > 1 {
      if with-bullets { render-bullets(entry, start: 1) }
      else { render-plain-lines(entry, start: 1) }
    }
  } else {
    block(breakable: false, {
      if kind == "EDUCATION" { render-education-header(entry) }
      else { render-experience-header(entry) }
    })
    if with-bullets { render-bullets(entry) }
    else { render-plain-lines(entry) }
  }
  v(0.16em)
}

#let render-skill-entry(entry) = {
  let items = entry.skill-items.join("、")
  if items != "" {
    if entry.group != "" {
      text(weight: "bold", entry.group + "：")
    }
    text(items)
    linebreak()
    v(0.08em)
  }
}

#let render-generic-lines(entries, start-entry: 0, start-bullet: 0) = {
  for entry-index in range(start-entry, entries.len()) {
    let entry = entries.at(entry-index)
    let from = if entry-index == start-entry { start-bullet } else { 0 }
    for bullet-index in range(from, entry.bullets.len()) {
      let bullet = entry.bullets.at(bullet-index)
      if bullet != "" {
        text(bullet)
        linebreak()
        v(0.10em)
      }
    }
  }
}

#let render-generic-values(section) = {
  let values = ()
  for entry in section.entries {
    for bullet in entry.bullets {
      if bullet != "" { values.push(bullet) }
    }
  }
  if values.len() == 0 { return }
  let content = values.join(" · ")
  if content.len() <= 600 {
    block(breakable: false, {
      section-title(section.title)
      text(content)
    })
  } else {
    block(breakable: false, section-title(section.title))
    text(content)
  }
  v(0.08em)
}

#let render-generic-section(section) = {
  if section.kind == "CERTIFICATE" or section.kind == "ACHIEVEMENT" {
    render-generic-values(section)
    return
  }
  let entries = section.entries
  let first-entry = entries.at(0)
  let first-bullet = if first-entry.bullets.len() > 0 { first-entry.bullets.at(0) } else { "" }
  if first-bullet != "" and first-bullet.len() <= 600 {
    block(breakable: false, {
      section-title(section.title)
      text(first-bullet)
      linebreak()
    })
    render-generic-lines(entries, start-bullet: 1)
  } else {
    block(breakable: false, section-title(section.title))
    render-generic-lines(entries)
  }
  v(0.06em)
}

#let render-skill-section(section) = {
  let entries = section.entries
  let first = entries.at(0)
  let first-items = first.skill-items.join("、")
  if first-items != "" {
    block(breakable: false, {
      section-title(section.title)
      render-skill-entry(first)
    })
    if entries.len() > 1 {
      for entry in entries.slice(1) { render-skill-entry(entry) }
    }
  } else {
    block(breakable: false, section-title(section.title))
    for entry in entries { render-skill-entry(entry) }
  }
  v(0.04em)
}

#let render-structured-section(section) = {
  let entries = section.entries
  let first = entries.at(0)
  let first-bullet = if first.bullets.len() > 0 { first.bullets.at(0) } else { "" }
  let keep-first = first-bullet != "" and first-bullet.len() <= 600
  if keep-first {
    block(breakable: false, {
      section-title(section.title)
      if section.kind == "EDUCATION" { render-education-header(first) }
      else { render-experience-header(first) }
      if section.kind == "EXPERIENCE" or section.kind == "PROJECT" {
        render-bullets(first, end: 1)
      } else {
        render-plain-lines(first, end: 1)
      }
    })
    if first.bullets.len() > 1 {
      if section.kind == "EXPERIENCE" or section.kind == "PROJECT" { render-bullets(first, start: 1) }
      else { render-plain-lines(first, start: 1) }
    }
  } else {
    block(breakable: false, {
      section-title(section.title)
      if section.kind == "EDUCATION" { render-education-header(first) }
      else { render-experience-header(first) }
    })
    if section.kind == "EXPERIENCE" or section.kind == "PROJECT" { render-bullets(first) }
    else { render-plain-lines(first) }
  }
  v(0.16em)
  if entries.len() > 1 {
    for entry in entries.slice(1) { render-structured-entry(entry, section.kind) }
  }
}

#let render-section(section) = {
  if section.entries.len() == 0 { return }
  if section.kind == "SKILL" { render-skill-section(section) }
  else if section.kind == "EXPERIENCE" or section.kind == "PROJECT" or section.kind == "EDUCATION" {
    render-structured-section(section)
  } else {
    render-generic-section(section)
  }
}

#render-header()
#for section in sections { render-section(section) }
